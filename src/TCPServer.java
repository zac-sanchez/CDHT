import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.*;

public class TCPServer implements Runnable {

    private static final String threadName = "TCPServer";
    private Thread t;
    private cdht peer;
    private ServerSocket TCPSocket;
    private volatile boolean shutdown = false;

    /**
     * Instantiates the TCP server.
     * @param peer
     */
    public TCPServer(cdht peer) {
        this.peer = peer;
    }

    /**
     * Runs the main thread loop.
     */
    public void run() {
        startTCPServer();
    }

    /**
     * Starts the thread.
     */
    public void start() {
        if (this.t == null) {
            this.t = new Thread (this, threadName);
            this.t.start();
        }
    }

    /**
     * Shuts down the thread by modifying controlling variable.
     */
    public void shutdown() throws IOException {
        this.TCPSocket.close();
        this.shutdown = true;
    }

    private void startTCPServer() {
        int port = cdht.getPort(peer.getPeer());
        try {
            this.TCPSocket = new ServerSocket(port, 0, InetAddress.getByName("localhost"));
            while (!shutdown) {
                Socket tcps = TCPSocket.accept();
                BufferedReader tcp_reader = new BufferedReader(new InputStreamReader(tcps.getInputStream()));
                String tcp_message = tcp_reader.readLine().trim();
                parseTCPRequest(tcp_message);
            }
        } catch (IOException e) {
            return;
        }
    }

    /**
     * Parses a TCP message and directs decision to File Request or graceful quit.
     * @param tcp_message
     */
    private void parseTCPRequest(String tcp_message) {

        String message_type = extractType(tcp_message.trim());
        String sending_peer = extractSendingPeer(tcp_message.trim());
        String payload = extractPayload(tcp_message.trim());

        if (message_type.equals("FR")) {
            processFileRequest(sending_peer, payload);
        } else if (message_type.equals("GQ")) {
            processGracefulQuit(sending_peer, payload);
        } else if (message_type.equals("DP")) {
            processDeadPeer(sending_peer, payload);
        }
    }

    /**
     * Processes a file request from a peer.
     * @param sending_peer
     * @param payload
     */
    private void processFileRequest(String sending_peer, String payload) {
        System.out.println("I have received a file request.");
    }

    /**
     * Processes a graceful quit from a peer.
     * @param sending_peer
     * @param payload
     */
    private void processGracefulQuit(String sending_peer, String payload) {
        System.out.println(String.format("Peer %s will depart from the network.", sending_peer));
        
        // convert the numbers in the payload to integers.
        int first_pred = Integer.parseInt(payload.split(" ")[0]);
        int second_pred = Integer.parseInt(payload.split(" ")[1]);

        System.out.println("My first successor is now peer " + first_pred);
        System.out.println("My second successor is now peer " + second_pred);

        // Update the successors of the peer.
        this.peer.setFirstSuccessor(first_pred);
        this.peer.setSecondSuccessor(second_pred);
    }

    /**
     * Server side handing of the dead peer.
     * @param sending_peer
     * @param payload
     */
    private void processDeadPeer(String sending_peer, String payload) {
        String[] payload_data = payload.split(" ");
        int query = Integer.parseInt(payload_data[0]);
        
        int new_successor = -1;
        if (payload_data.length == 2) {
            new_successor = Integer.parseInt(payload_data[1]);
        }

        if (query) {
            processQuery(sending_peer);
        } else {
            processResponse(payload);
        }
    }

    private void processQuery(String sending_peer) {
        System.out.println(String.format("Responding to %s.", sending_peer));
    }

    private void processResponse(String payload) {
        System.out.println(String.format("Processing query %s.", payload));
    }
    
    //====================HELPER FUNCTIONS FOR EXTRACTING TCP MESSAGE DATA==============================//

    /**
     * Retrieves the TCP message type from the tcp_message. Refer to TCP message protocol in cdht.java.
     * @param tcp_message
     * @return the message type as a string.
     */
    private String extractType(String tcp_message) {
        return tcp_message.split(" ")[0];
    }

    /**
     * Retrieves the TCP sending peer from the tcp_message. Refer to TCP message protocol in cdht.java.
     * @param tcp_message
     * @return the sending peer as a string.
     */
    private String extractSendingPeer(String tcp_message) {
        return tcp_message.split(" ")[1];
    }

    /**
     * Retrieves the TCP payload from the tcp_message. Refer to TCP message protocol in cdht.java.
     * @return the payload string
     */
    private String extractPayload(String tcp_message) {
        // When there is a payload.
        return tcp_message.split(" ")[2] + " " + tcp_message.split(" ")[3];
    }

    

}