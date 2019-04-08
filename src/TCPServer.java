import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.*;

public class TCPServer implements Runnable {

    private static final String threadName = "TCPServer";
    private Thread t;
    private cdht peer;
    private int peer_id;
    private ServerSocket TCPSocket;

    /**
     * Instantiates the TCP server.
     * @param peer
     */
    public TCPServer(cdht peer) {
        this.peer = peer;
        this.peer_id = peer.peer_id;
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
    public void start () {
        if (t == null) {
            t = new Thread (this, threadName);
            t.start ();
        }
    }

    private void startTCPServer() {
        int port = cdht.getPort(peer_id);
        try {
            this.TCPSocket = new ServerSocket(port, 0, InetAddress.getByName("localhost"));
            while (true) {
                Socket tcps = TCPSocket.accept();
                BufferedReader tcp_reader = new BufferedReader(new InputStreamReader(tcps.getInputStream()));
                String tcp_message = tcp_reader.readLine();
                System.out.println(tcp_message);
                parseTCPRequest(tcp_message);
            }

        } catch (IOException e) {
            System.err.println(e);
        }
        
    }

    /**
     * Closes the TCP Socket attached to the server.
     */
    private void closeTCPServer() {
        this.TCPSocket.close();
    }

    /**
     * Parses a TCP message and directs decision to File Request or graceful quit.
     * @param tcp_message
     */
    private void parseTCPRequest(String tcp_message) {
        String message_type = extractType(tcp_message);
        String sending_peer = extractSendingPeer(tcp_message);
        String payload = extractPayload(tcp_message);

        if (message_type == "FR") {
            processFileRequest(sending_peer, payload);
        } else if (message_type == "GQ") {
            processGracefulQuit(sending_peer, payload);
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
        System.out.println("I have received a graceful quit request.");
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
        if (tcp_message.length() == 3) {
            return tcp_message.split(" ")[2];
        } else {
            return tcp_message.split(" ")[2] + tcp_message.split(" ")[3];
        }
    }

    

}