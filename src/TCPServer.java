import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.DataOutputStream;
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
     * 
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
            this.t = new Thread(this, threadName);
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

    /**
     * Starts an ongoing TCP server.
     */
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
     * 
     * @param tcp_message
     */
    private void parseTCPRequest(String tcp_message) {

        String message_type = extractType(tcp_message.trim());
        int[] message_fields = getMessageFields(tcp_message.trim());

        if (message_type.equals("FR")) {
            processFileRequest(message_fields);
        } else if (message_type.equals("GQ")) {
            processGracefulQuit(message_fields);
        } else if (message_type.equals("DP")) {
            processDeadPeer(message_fields);
        }
    }

    /**
     * Processes a file request from a peer.
     * 
     * @param sending_peer
     * @param payload
     */
    private void processFileRequest(int[] message_fields) {
        System.out.println("I have received a file request.");
    }

    /**
     * Processes a graceful quit from a peer.
     * 
     * @param sending_peer
     * @param payload
     */
    private void processGracefulQuit(int[] message_fields) {
        int sending_peer = message_fields[0];
        int first_pred = message_fields[1];
        int second_pred = message_fields[2];

        System.out.println(String.format("Peer %s will depart from the network.", sending_peer));

        // convert the numbers in the payload to integers.
        System.out.println("My first successor is now peer " + first_pred);
        System.out.println("My second successor is now peer " + second_pred);

        // Update the successors of the peer.
        this.peer.setFirstSuccessor(first_pred);
        this.peer.setSecondSuccessor(second_pred);
    }

    /**
     * Server side handing of the dead peer.
     * 
     * @param sending_peer
     * @param payload
     */
    private void processDeadPeer(int[] message_fields) {

        int sending_peer = message_fields[0];
        int query_flag = message_fields[1];
        int new_successor = message_fields[2];

        if (query_flag == 1) {
            processQuery(sending_peer);
        } else {
            processKillResponse(new_successor);
        }
    }

    /**
     * Function to send a TCP response message to a querying peer.
     * @param sending_peer
     */
    private void processQuery(int sending_peer) {
        // Create a TCP socket to send the
        try {
            Socket sendSocket = new Socket("localhost", cdht.getPort(sending_peer));
            DataOutputStream messageStream = new DataOutputStream(sendSocket.getOutputStream());
            // Create the TCP Message and send it.
            String msg = createKillResponse();
            messageStream.writeBytes(msg);
            sendSocket.close();
        } catch (UnknownHostException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Function to process a TCP kill response that sets the peers new successors.
     * @param new_successor
     * @param sending_peer
     */
    private void processKillResponse(int new_successor) {
        System.out.println("My second successor is now peer " + new_successor + ".");
        this.peer.setSecondSuccessor(new_successor);
    }

    /**
     * Function to create a TCP kill response message to send back to querying peer.
     * @return message in format outlined in cdht "DP". [DP] [sending_peer] [query_flag] [new_successor_id]
     */
    private String createKillResponse() {
        // Creates a responding message with data about peers next sucessor.
        return "DP" + " " + this.peer.getPeer() + " " + 0 + " " + this.peer.getFirstSuccessor();
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
     * Extracts the message data from a tcp kill peer message received from another peer.
     * 
     * @param tcp_message
     * @return intger array with the 3 data fields [sending_peer] [query flag] [new successor]
     */
    private int[] getMessageFields(String tcp_message) {
        // 
        String[] string_fields = tcp_message.split(" ");
        int[] msg_field_data = new int[3];

        for (int i = 1; i < string_fields.length; i++) {
            msg_field_data[i-1] = Integer.parseInt(string_fields[i]);
        }
        return msg_field_data;
    }
}