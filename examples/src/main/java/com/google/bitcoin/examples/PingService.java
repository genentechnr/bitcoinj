/**
 * Copyright 2011 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.bitcoin.examples;

import com.google.bitcoin.core.*;
import com.google.bitcoin.discovery.DnsDiscovery;
import com.google.bitcoin.discovery.IrcDiscovery;
import com.google.bitcoin.store.BlockStore;
import com.google.bitcoin.store.BoundedOverheadBlockStore;
import com.google.bitcoin.utils.BriefLogFormatter;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;

import java.io.File;
import java.io.IOException;
import java.math.BigInteger;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * <p>
 * PingService demonstrates basic usage of the library. It sits on the network and when it receives coins, simply
 * sends them right back to the previous owner, determined rather arbitrarily by the address of the first input.
 * </p>
 *
 * <p>
 * If running on TestNet (slow but better than using real coins on prodnet) do the following:
 * <ol>
 * <li>Backup your current wallet.dat in case of unforeseen problems</li>
 * <li>Start your bitcoin client in test mode <code>bitcoin -testnet</code>. This will create a new sub-directory called testnet and should not interfere with normal wallets or operations.</li>
 * <li>(Optional) Choose a fresh address</li>
 * <li>(Optional) Visit the Testnet faucet (https://testnet.freebitcoins.appspot.com/) to load your client with test coins</li>
 * <li>Run <code>PingService testnet</code></li>
 * <li>Wait for the block chain to download</li>
 * <li>Send some coins from your bitcoin client to the address provided in the PingService console</li>
 * <li>Leave it running until you get the coins back again</li>
 * </ol>
 * </p>
 *
 * <p>The testnet can be slow or flaky as it's a shared resource. You can use the <a href="http://sourceforge
 * .net/projects/bitcoin/files/Bitcoin/testnet-in-a-box/">testnet in a box</a> to do everything purely locally.</p>
 */
public class PingService {
    private final PeerGroup peerGroup;
    private final BlockChain chain;
    private final BlockStore blockStore;
    private final File walletFile;

    public static void main(String[] args) throws Exception {
        BriefLogFormatter.init();
        new PingService(args);
    }

    public PingService(String[] args) throws Exception {
        boolean testNet = args.length > 0 && args[0].equalsIgnoreCase("testnet");
        final NetworkParameters params = testNet ? NetworkParameters.testNet() : NetworkParameters.prodNet();
        String filePrefix = testNet ? "pingservice-testnet" : "pingservice-prodnet";
        // Try to read the wallet from storage, create a new one if not possible.
        walletFile = new File(filePrefix + ".wallet");
        Wallet w;
        try {
            w = Wallet.loadFromFile(walletFile);
        } catch (IOException e) {
            w = new Wallet(params);
            w.keychain.add(new ECKey());
            w.saveToFile(walletFile);
        }
        final Wallet wallet = w;
        // Fetch the first key in the wallet (should be the only key).
        ECKey key = wallet.keychain.get(0);
        // Load the block chain, if there is one stored locally.
        System.out.println("Reading block store from disk");
        blockStore = new BoundedOverheadBlockStore(params, new File(filePrefix + ".blockchain"));
        // Connect to the localhost node. One minute timeout since we won't try any other peers
        System.out.println("Connecting ...");
        chain = new BlockChain(params, wallet, blockStore);
        peerGroup = new PeerGroup(params, chain);
        peerGroup.setUserAgent("PingService", "1.0");
        if (testNet) {
            peerGroup.addPeerDiscovery(new IrcDiscovery("#bitcoinTEST3"));
        } else {
            peerGroup.addPeerDiscovery(new DnsDiscovery(params));
        }
        peerGroup.addWallet(wallet);

        // We want to know when the balance changes.
        wallet.addEventListener(new AbstractWalletEventListener() {
            @Override
            public void onCoinsReceived(Wallet w, Transaction tx, BigInteger prevBalance, BigInteger newBalance) {
                // Running on a peer thread.
                assert !newBalance.equals(BigInteger.ZERO);
                if (!tx.isPending()) return;
                // It was broadcast, but we can't really verify it's valid until it appears in a block.
                BigInteger value = tx.getValueSentToMe(w);
                System.out.println("Received pending tx for " + Utils.bitcoinValueToFriendlyString(value) +
                        ": " + tx);
                tx.getConfidence().addEventListener(new TransactionConfidence.Listener() {
                    public void onConfidenceChanged(Transaction tx2) {
                        if (tx2.getConfidence().getConfidenceType() == TransactionConfidence.ConfidenceType.BUILDING) {
                            // Coins were confirmed (appeared in a block).
                            tx2.getConfidence().removeEventListener(this);
                            bounceCoins(wallet, tx2);
                        } else {
                            System.out.println(String.format("Confidence of %s changed, is now: %s",
                                    tx2.getHashAsString(), tx2.getConfidence().toString()));
                        }
                    }
                });
            }
        });

        peerGroup.startAndWait();
        peerGroup.downloadBlockChain();
        System.out.println("Send coins to: " + key.toAddress(params).toString());
        System.out.println("Waiting for coins to arrive. Press Ctrl-C to quit.");
        try {
            Thread.sleep(Long.MAX_VALUE);
        } catch (InterruptedException e) {}
        peerGroup.stopAndWait();
        wallet.saveToFile(walletFile);
        blockStore.close();
    }

    private void bounceCoins(final Wallet wallet, Transaction tx) {
        // It's impossible to pick one specific identity that you receive coins from in Bitcoin as there
        // could be inputs from many addresses. So instead we just pick the first and assume they were all
        // owned by the same person.
        try {
            BigInteger value = tx.getValueSentToMe(wallet);
            TransactionInput input = tx.getInputs().get(0);
            Address from = input.getFromAddress();
            System.out.println("Received " + Utils.bitcoinValueToFriendlyString(value) + " from " + from.toString());
            // Now send the coins back!
            final Wallet.SendResult sendResult = wallet.sendCoins(peerGroup, from, value);
            checkNotNull(sendResult);  // We should never try to send more coins than we have!
            System.out.println("Sending ...");
            Futures.addCallback(sendResult.broadcastComplete, new FutureCallback<Transaction>() {
                public void onSuccess(Transaction transaction) {
                    System.out.println("Sent coins back! Transaction hash is " + sendResult.tx.getHashAsString());
                    try {
                        wallet.saveToFile(walletFile);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }

                public void onFailure(Throwable throwable) {
                    System.err.println("Failed to send coins :(");
                    throwable.printStackTrace();
                }
            });
        } catch (ScriptException e) {
            // If we didn't understand the scriptSig, just crash.
            throw new RuntimeException(e);
        }
    }
}
