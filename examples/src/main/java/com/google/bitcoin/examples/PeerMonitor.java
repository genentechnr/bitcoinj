/*
 * Copyright 2012 Google Inc.
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

import com.google.bitcoin.core.AbstractPeerEventListener;
import com.google.bitcoin.core.NetworkParameters;
import com.google.bitcoin.core.Peer;
import com.google.bitcoin.core.PeerGroup;
import com.google.bitcoin.discovery.DnsDiscovery;
import com.google.bitcoin.utils.BriefLogFormatter;

import javax.swing.*;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableCellRenderer;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.List;

/**
 * Shows connected peers in a table view, so you can watch as they come and go.
 */
public class PeerMonitor {
    private NetworkParameters params;
    private PeerGroup peerGroup;
    private PeerTableModel peerTableModel;
    private PeerTableRenderer peerTableRenderer;

    public static void main(String[] args) throws Exception {
        BriefLogFormatter.init();
        new PeerMonitor();
    }

    public PeerMonitor() {
        setupNetwork();
        setupGUI();
        peerGroup.start();
    }

    private void setupNetwork() {
        params = NetworkParameters.prodNet();
        peerGroup = new PeerGroup(params, null /* no chain */);
        peerGroup.setUserAgent("PeerMonitor", "1.0");
        peerGroup.setMaxConnections(4);
        peerGroup.addPeerDiscovery(new DnsDiscovery(params));
        peerGroup.addEventListener(new AbstractPeerEventListener() {
            @Override
            public void onPeerConnected(final Peer peer, int peerCount) {
                refreshUI();
            }

            @Override
            public void onPeerDisconnected(Peer peer, int peerCount) {
                refreshUI();
            }
        });
    }

    private void refreshUI() {
        // Tell the Swing UI thread to redraw the peers table.
        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                peerTableModel.updateFromPeerGroup();
            }
        });
    }

    private void setupGUI() {
        JFrame window = new JFrame("Network monitor");
        window.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        window.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent windowEvent) {
                System.out.println("Shutting down ...");
                peerGroup.stopAndWait();
                System.out.println("Shutdown complete.");
                System.exit(0);
            }
        });

        JPanel panel = new JPanel();
        JLabel instructions = new JLabel("Number of peers to connect to: ");
        final SpinnerNumberModel spinnerModel = new SpinnerNumberModel(4, 0, 100, 1);
        spinnerModel.addChangeListener(new ChangeListener() {
            public void stateChanged(ChangeEvent changeEvent) {
                peerGroup.setMaxConnections(spinnerModel.getNumber().intValue());
            }
        });
        JSpinner numPeersSpinner = new JSpinner(spinnerModel);
        panel.add(instructions);
        panel.add(numPeersSpinner);
        window.getContentPane().add(panel, BorderLayout.NORTH);

        peerTableModel = new PeerTableModel();
        JTable peerTable = new JTable(peerTableModel);
        peerTable.setAutoCreateRowSorter(true);
        peerTableRenderer = new PeerTableRenderer(peerTableModel);
        peerTable.setDefaultRenderer(String.class, peerTableRenderer);
        peerTable.setDefaultRenderer(Integer.class, peerTableRenderer);
        peerTable.setDefaultRenderer(Long.class, peerTableRenderer);

        JScrollPane scrollPane = new JScrollPane(peerTable);
        window.getContentPane().add(scrollPane, BorderLayout.CENTER);
        window.pack();
        window.setSize(640, 480);
        window.setVisible(true);

        // Refresh the UI every half second to get the latest ping times. The event handler runs in the UI thread.
        new Timer(1000, new ActionListener() {
            public void actionPerformed(ActionEvent actionEvent) {
                peerTableModel.updateFromPeerGroup();
            }
        }).start();
    }

    private class PeerTableModel extends AbstractTableModel {
        public static final int IP_ADDRESS = 0;
        public static final int PROTOCOL_VERSION = 1;
        public static final int USER_AGENT = 2;
        public static final int CHAIN_HEIGHT = 3;
        public static final int PING_TIME = 4;
        public static final int LAST_PING_TIME = 5;

        public List<Peer> connectedPeers;
        public List<Peer> pendingPeers;

        public void updateFromPeerGroup() {
            connectedPeers = peerGroup.getConnectedPeers();
            pendingPeers = peerGroup.getPendingPeers();
            fireTableDataChanged();
        }

        public int getRowCount() {
            return peerGroup.numConnectedPeers() + peerGroup.getPendingPeers().size();
        }

        @Override
        public String getColumnName(int i) {
            switch (i) {
                case IP_ADDRESS: return "IP address";
                case PROTOCOL_VERSION: return "Protocol version";
                case USER_AGENT: return "User Agent";
                case CHAIN_HEIGHT: return "Chain height";
                case PING_TIME: return "Average ping";
                case LAST_PING_TIME: return "Last ping";
                default: throw new RuntimeException();
            }
        }

        public int getColumnCount() {
            return 6;
        }

        public Class<?> getColumnClass(int column) {
            switch (column) {
                case PROTOCOL_VERSION:
                    return Integer.class;
                case CHAIN_HEIGHT:
                case PING_TIME:
                case LAST_PING_TIME:
                    return Long.class;
                default:
                    return String.class;
            }
        }

        public Object getValueAt(int row, int col) {
            // This is racy. A peer can be moving from pending to connected between these two lines.
            List<Peer> peers = peerGroup.getConnectedPeers();
            List<Peer> pendingPeers = peerGroup.getPendingPeers();
            if (row >= peers.size()) {
                // Peer that isn't connected yet.
                Peer peer = pendingPeers.get(row - peers.size());
                switch (col) {
                    case IP_ADDRESS:
                        return peer.getAddress().getAddr().getHostAddress();
                    case PROTOCOL_VERSION:
                        return 0;
                    case CHAIN_HEIGHT:
                    case PING_TIME:
                    case LAST_PING_TIME:
                        return 0L;
                    default:
                        return "(pending)";
                }
            }
            Peer peer = peers.get(row);
            switch (col) {
                case IP_ADDRESS:
                    return peer.getAddress().getAddr().getHostAddress();
                case PROTOCOL_VERSION:
                    return Integer.toString(peer.getPeerVersionMessage().clientVersion);
                case USER_AGENT:
                    return peer.getPeerVersionMessage().subVer;
                case CHAIN_HEIGHT:
                    return peer.getBestHeight();
                case PING_TIME:
                case LAST_PING_TIME:
                    long time = col == PING_TIME ? peer.getPingTime() : peer.getLastPingTime();
                    if (time == Long.MAX_VALUE)
                        return 0L;
                    else
                        return time;

                default: throw new RuntimeException();
            }
        }
    }

    private class PeerTableRenderer extends JLabel implements TableCellRenderer {
        private final PeerTableModel model;
        private final Font normal, bold;

        public PeerTableRenderer(PeerTableModel model) {
            super();
            this.model = model;
            this.normal = new Font("Sans Serif", Font.PLAIN, 12);
            this.bold = new Font("Sans Serif", Font.BOLD, 12);
        }

        public Component getTableCellRendererComponent(JTable table, Object contents,
                                                       boolean selected, boolean hasFocus, int row, int column) {
            setText(contents.toString());
            if (model.connectedPeers == null || model.pendingPeers == null) {
                return this;
            }

            if (row >= model.connectedPeers.size()) {
                setFont(normal);
                setForeground(Color.LIGHT_GRAY);
            } else {
                if (model.connectedPeers.get(row).getDownloadData())
                    setFont(bold);
                else
                    setFont(normal);
                setForeground(Color.BLACK);

                // Mark chain heights that aren't normal.
                if (column == PeerTableModel.CHAIN_HEIGHT) {
                    long height = (Long) contents;
                    if (height != peerGroup.getMostCommonChainHeight()) {
                        setText(height + " • ");
                    }
                }
            }
            return this;
        }
    }
}
