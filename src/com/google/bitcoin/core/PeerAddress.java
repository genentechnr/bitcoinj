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

package com.google.bitcoin.core;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.Date;

import static com.google.bitcoin.core.Utils.uint32ToByteStreamLE;
import static com.google.bitcoin.core.Utils.uint64ToByteStreamLE;

/**
 * A PeerAddress holds an IP address and port number representing the network location of
 * a peer in the BitCoin P2P network. It exists primarily for serialization purposes.
 */
public class PeerAddress extends ChildMessage {
    private static final long serialVersionUID = 7501293709324197411L;

    InetAddress addr;
    int port;
    BigInteger services;
    long time;

    /**
     * Construct a peer address from a serialized payload.
     */
    public PeerAddress(NetworkParameters params, byte[] payload, int offset, int protocolVersion) throws ProtocolException {
        super(params, payload, offset, protocolVersion);
    }
    

    /**
     * Construct a peer address from a serialized payload.
     */
	public PeerAddress(NetworkParameters params, byte[] msg, int offset, int protocolVersion, Message parent, boolean parseLazy,
			boolean parseRetain) throws ProtocolException {
		super(params, msg, offset, protocolVersion, parent, parseLazy, parseRetain);
	}



	/**
     * Construct a peer address from a memorized or hardcoded address.
     */
    public PeerAddress(InetAddress addr, int port, int protocolVersion) {
        this.addr = addr;
        this.port = port;
        this.protocolVersion = protocolVersion;
        this.services = BigInteger.ZERO;
    }
    
    public PeerAddress(InetAddress addr, int port) {
        this(addr, port, NetworkParameters.PROTOCOL_VERSION);
    }
    
    public PeerAddress(InetAddress addr) {
        this(addr, 0);
    }
    
    public PeerAddress(InetSocketAddress addr) {
        this(addr.getAddress(), addr.getPort());
    }

	@Override
	protected void bitcoinSerializeToStream(OutputStream stream) throws IOException {
        if (protocolVersion >= 31402) {
            int secs = (int)(Utils.now().getTime() / 1000);
            uint32ToByteStreamLE(secs, stream);
        }
        uint64ToByteStreamLE(services, stream);  // nServices.
        // Java does not provide any utility to map an IPv4 address into IPv6 space, so we have to do it by hand.
        byte[] ipBytes = addr.getAddress();
        if (ipBytes.length == 4) {
            byte[] v6addr = new byte[16];
            System.arraycopy(ipBytes, 0, v6addr, 12, 4);
            v6addr[10] = (byte) 0xFF;
            v6addr[11] = (byte) 0xFF;
            ipBytes = v6addr;
        }
        stream.write(ipBytes);
        // And write out the port. Unlike the rest of the protocol, address and port is in big endian byte order.
        stream.write((byte) (0xFF & port >> 8));
        stream.write((byte) (0xFF & port));
    }

    @Override
    protected void parse() {
        // Format of a serialized address:
        //   uint32 timestamp
        //   uint64 services   (flags determining what the node can do)
        //   16 bytes ip address
        //   2 bytes port num
        if (protocolVersion > 31402)
            time = readUint32();
        else
            time = -1;
        services = readUint64();
        byte[] addrBytes = readBytes(16);
        try {
            addr = InetAddress.getByAddress(addrBytes);
        } catch (UnknownHostException e) {
            throw new RuntimeException(e);  // Cannot happen.
        }
        port = ((0xFF & bytes[cursor++]) << 8) | (0xFF & bytes[cursor++]);
    }

    @Override
    public String toString() {
        return "[" + addr.getHostAddress() + "]:" + port;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof PeerAddress)) return false;
        PeerAddress other = (PeerAddress) o;
        return other.addr.equals(addr) &&
               other.port == port &&
               other.services.equals(services) &&
               other.time == time;
    }

    @Override
    public int hashCode() {
        return addr.hashCode() ^ port ^ (int)time ^ services.hashCode();
    }
}
