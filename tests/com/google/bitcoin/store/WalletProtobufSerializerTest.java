package com.google.bitcoin.store;


import static com.google.bitcoin.core.TestUtils.createFakeTx;
import static com.google.bitcoin.core.Utils.toNanoCoins;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;

import org.bitcoinj.wallet.Protos;
import org.junit.Before;
import org.junit.Test;

import com.google.bitcoin.core.Address;
import com.google.bitcoin.core.AddressFormatException;
import com.google.bitcoin.core.BlockChain;
import com.google.bitcoin.core.ECKey;
import com.google.bitcoin.core.NetworkParameters;
import com.google.bitcoin.core.Transaction;
import com.google.bitcoin.core.Utils;
import com.google.bitcoin.core.Wallet;

import static org.junit.Assert.*;

public class WalletProtobufSerializerTest {
    static final NetworkParameters params = NetworkParameters.unitTests();
    private ECKey myKey;
    private Address myAddress;
    private Wallet wallet;
    private MemoryBlockStore blockStore;

    @Before
    public void setUp() throws Exception {
        myKey = new ECKey();
        myAddress = myKey.toAddress(params);
        wallet = new Wallet(params);
        wallet.addKey(myKey);
        blockStore = new MemoryBlockStore(params);
    }

    @Test
    public void testSimple() throws Exception {
        Wallet wallet1 = roundTrip(wallet);
        assertEquals(0, wallet1.getTransactions(true, true).size());
        assertEquals(BigInteger.ZERO, wallet1.getBalance());
        
        BigInteger v1 = Utils.toNanoCoins(1, 0);
        Transaction t1 = createFakeTx(params, v1, myAddress);

        wallet.receiveFromBlock(t1, null, BlockChain.NewBlockType.BEST_CHAIN);
        
        wallet1 = roundTrip(wallet);
        assertArrayEquals(myKey.getPubKey(), wallet1.findKeyFromPubHash(myKey.getPubKeyHash()).getPubKey());
        assertArrayEquals(myKey.getPrivKeyBytes(), wallet1.findKeyFromPubHash(myKey.getPubKeyHash()).getPrivKeyBytes());
        assertEquals(1, wallet1.getTransactions(true, true).size());
        assertEquals(v1, wallet1.getBalance());
        assertArrayEquals(t1.bitcoinSerialize(),wallet1.getTransaction(t1.getHash()).bitcoinSerialize());
        
        Protos.Wallet walletProto = WalletProtobufSerializer.walletToProto(wallet);
        assertEquals(Protos.Key.Type.ORIGINAL, walletProto.getKey(0).getType());
        assertEquals(0, walletProto.getExtensionCount());
        assertEquals(1, walletProto.getTransactionCount());
        assertEquals(1, walletProto.getKeyCount());
        
        Protos.Transaction t1p = walletProto.getTransaction(0);
        assertEquals(0, t1p.getBlockHashCount());
        assertArrayEquals(t1.getHash().getBytes(), t1p.getHash().toByteArray());
        assertEquals(Protos.Transaction.Pool.UNSPENT, t1p.getPool());
        assertFalse(t1p.hasLockTime());
        assertFalse(t1p.getTransactionInput(0).hasSequence());
        assertArrayEquals(t1.getInputs().get(0).getOutpoint().getHash().getBytes(), t1p.getTransactionInput(0).getTransactionOutPointHash().toByteArray());
        assertEquals(0, t1p.getTransactionInput(0).getTransactionOutPointIndex());
        assertEquals(t1p.getTransactionOutput(0).getValue(), v1.longValue());
        
        ECKey k2 = new ECKey();
        BigInteger v2 = toNanoCoins(0, 50);
        Transaction t2 = wallet.sendCoinsOffline(k2.toAddress(params), v2);
        wallet1 = roundTrip(wallet);
        Transaction t1r = wallet1.getTransaction(t1.getHash());
        Transaction t2r = wallet1.getTransaction(t2.getHash());
        assertArrayEquals(t2.bitcoinSerialize(), t2r.bitcoinSerialize());
        assertArrayEquals(t1.bitcoinSerialize(), t1r.bitcoinSerialize());
        assertEquals(t1r.getOutputs().get(0).getSpentBy(), t2r.getInputs().get(0));

        assertEquals(1, wallet1.getPendingTransactions().size());
        assertEquals(2, wallet1.getTransactions(true, true).size());
    }
    
    @Test
    public void testKeys() throws Exception {
        for (int i = 0 ; i < 20 ; i++) {
            myKey = new ECKey();
            myAddress = myKey.toAddress(params);
            wallet = new Wallet(params);
            wallet.addKey(myKey);
            Wallet wallet1 = roundTrip(wallet);
            assertArrayEquals(myKey.getPubKey(), wallet1.findKeyFromPubHash(myKey.getPubKeyHash()).getPubKey());
            assertArrayEquals(myKey.getPrivKeyBytes(), wallet1.findKeyFromPubHash(myKey.getPubKeyHash()).getPrivKeyBytes());
        }
    }

    private Wallet roundTrip(Wallet wallet) throws IOException, AddressFormatException, BlockStoreException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        //System.out.println(WalletProtobufSerializer.walletToText(wallet));
        WalletProtobufSerializer.writeWallet(wallet, output);
        ByteArrayInputStream input = new ByteArrayInputStream(output.toByteArray());
        return WalletProtobufSerializer.readWallet(input, params, blockStore);
    }
}
