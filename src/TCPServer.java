import java.net.*;
import java.io.*;

public class TCPServer implements Runnable {

    private static final String threadName = "TCPServer";
    private Thread t;
    private cdht peer;
    private ServerSocket TCPSocket;
    private volatile boolean shutdown = false;

    // Used for acknowledging that both successors have received a graceful departure message.
    private boolean first_pred_rcvd = false;
    private boolean second_pred_rcvd = false;

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
    public void shutdown() {
        this.shutdown = true;
    }

    /**
     * Starts an ongoing TCP server.
     */
    private void startTCPServer() {
        int port = cdht.getPort(peer.getPeer());
        try {
            this.TCPSocket = new ServerSocket(port, 0, InetAddress.getByName("localhost"));
            while (!this.shutdown) {
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
     * @param message_fields an array of three integers that store [sending_peer] [file_name] [has_file]
     */
    private void processFileRequest(int[] message_fields) {
        int sending_peer = message_fields[0];
        int file_name = message_fields[1];
        int has_file = message_fields[2];
        int query = message_fields[3];

        if (query == 1) {
            // The message was a query.
            if (has_file == 1) {
                // If we have the file then send a response to the sending peer.
                this.sendResponseMessage(sending_peer, file_name);
                // Begin transferring the file.
                this.peer.initiateFileTransfer(sending_peer, file_name);
            } else {
                System.out.println("File " + file_name + " is not stored here.");
                System.out.println("File request mesage has been forwarded to my successor.");
                this.peer.fileRequest(file_name, sending_peer);
            }
        } else {
            // The message was a response message.
            System.out.println("Received a response message from peer " + sending_peer +
                               " which has the file " + file_name + ".");
            System.out.println("We now start receiving the file...");
        }
    }

    /**
     * Processes a graceful quit from a peer.
     * message_fields is an array of three integers that store [sending_peer] [first_pred] [second_pred] [query_flag]
     * @param message_fields 
     */
    private void processGracefulQuit(int[] message_fields) {
        int sending_peer = message_fields[0];
        int first_pred = message_fields[1];
        int second_pred = message_fields[2];
        int query_flag = message_fields[3];

        if (query_flag == 1) {
            System.out.println(String.format("Peer %s will depart from the network.", sending_peer));

            // convert the numbers in the payload to integers.
            System.out.println("My first successor is now peer " + first_pred);
            System.out.println("My second successor is now peer " + second_pred);
    
            // Update the successors of the peer.
            this.peer.setFirstSuccessor(first_pred);
            this.peer.setSecondSuccessor(second_pred);

            // Send an acknowledgement back to the quitting peer that we have received the quit message.
            sendGracefulQuitAck(sending_peer);
        } else {
            // Wait for acks from both predecessors before the peer quits.
            if (sending_peer == this.peer.getFirstPredecessor()) {this.first_pred_rcvd = true;}
            if (sending_peer == this.peer.getSecondPredecessor()) {this.second_pred_rcvd = true;}
            if (this.first_pred_rcvd && this.second_pred_rcvd) {
                this.shutdown();
            }
        }
    }

    private void sendGracefulQuitAck(int sending_peer) {
        try {
            // Set up the TCP Socket
            Socket sendSocket = new Socket("localhost", cdht.getPort(sending_peer));
            DataOutputStream messageStream = new DataOutputStream(sendSocket.getOutputStream());
            // [GQ] [SENDING_PEER] [FIRST_SUCC = 0 (unused)] [SECOND_SUCC = 0 (unused)] [QUERY_FLAG = 0]
            String quit_message = "GQ " + this.peer.getPeer() + " " + 0 + " " + 0 + " " + 0;
            messageStream.writeBytes(quit_message + "\n");
            sendSocket.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Server side handing of the dead peer.
     * 
     * @param message_fields an array of three integers that store [sending_peer] [query_flag] [new_successor]
     */
    private void processDeadPeer(int[] message_fields) {

        int sending_peer = message_fields[0];
        int query_flag = message_fields[1];
        int new_successor = message_fields[2];

        if (query_flag == 1) {
            processKillQuery(sending_peer);
        } else {
            processKillResponse(new_successor);
        }
    }

    /**
     * Sends a TCP Response message for a file request.
     * 
     * @param sending_peer The peer id of the requesting peer.
     * @param file_name The name of the file to be transferred.
     */
    private void sendResponseMessage(int sending_peer, int file_name) {
        System.out.println("File " + file_name + " is stored here.");
        System.out.println("A response message, destined for peer " + sending_peer + ", has been sent.");
        try {
            Socket sendSocket = new Socket("localhost", cdht.getPort(sending_peer));
            DataOutputStream messageStream = new DataOutputStream(sendSocket.getOutputStream());
            // Create the TCP Message and send it.
            String msg = createFileResponse(sending_peer, file_name);
            messageStream.writeBytes(msg);
            sendSocket.close();
        } catch (UnknownHostException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private String createFileResponse(int sending_peer, int file_name) {
        // FR [peer_id] [file_name] [1 (empty value for has_file)] [0 => it is a resopnse message]
        return "FR " + peer.getPeer() + " " + file_name + " " + 1 + " " + 0;
    }

    /**
     * Function to send a TCP response message to a querying peer.
     * @param sending_peer
     */
    private void processKillQuery(int sending_peer) {
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
        int[] msg_field_data = new int[string_fields.length];

        for (int i = 1; i < string_fields.length; i++) {
            msg_field_data[i-1] = Integer.parseInt(string_fields[i]);
        }
        return msg_field_data;
    }
}