import java.net.*;
import java.io.*;
import java.util.regex.*;
import java.time.Instant;

public class cdht {
    public static final int TRANSFER_HEADER_LEN = 20;
    public static final int DEFAULT_PORT = 50000;
    public static final int PING_FREQ = 20000;
    public static final int SOCKET_TIMEOUT_FREQ = 5000;
    public static final int MAX_FAILS = 2;

    private int peer_id;
    private int first_succ;
    private int second_succ;
    private int first_pred = -1;
    private int second_pred = -1;
    public Instant time;

    private int MSS;
    private float drop_prob;
    private PingServerUDP pingServer;
    private PingSenderUDP pingSenderFirst;
    private PingSenderUDP pingSenderSecond;
    private TCPServer tcpServer;
    private volatile boolean shutdown = false;

    public cdht(int peer_id, int first_succ_id, int second_succ_id, int MSS, float drop_prob) {
        this.peer_id = peer_id;
        this.first_succ = first_succ_id;
        this.second_succ = second_succ_id;
        this.MSS = MSS;
        this.drop_prob = drop_prob;
        this.time = Instant.now();
    }

    /**
     * Reads in arguments and initialises threads.
     * 
     * @param args
     */
    public static void main(String[] args) {
        cdht peer = null;
        if (args.length != 5) {
            System.err.println("Must specify arguments [peer_id] [first_successor_id] [second_successor_id] [MSS]"
                    + " [dropout_probability]");
            System.exit(1);
        }

        try {
            int peer_id = Integer.parseInt(args[0]);
            int first_succ_id = Integer.parseInt(args[1]);
            int second_succ_id = Integer.parseInt(args[2]);
            int MSS = Integer.parseInt(args[3]);
            float drop_prob = Float.parseFloat(args[4]);
            peer = new cdht(peer_id, first_succ_id, second_succ_id, MSS, drop_prob);
        } catch (NumberFormatException ex) {
            System.err.println("Error parsing arguments.");
            System.exit(1);
        }
        // Start the peer and all services.
        peer.initializeThreads();

        // Start loop for reading in terminal input.
        BufferedReader br = null;
        while (!peer.shutdown) {
            try {
                br = new BufferedReader(new InputStreamReader(System.in));
                String input_string = br.readLine();
                peer.parseUserInput(input_string);
            } catch (IOException e) {
                System.err.println(e);
            }
        }
    }

    /**
     * Initialises all threads that the peer has to run. A PingServer to respond to
     * pings. A PingSender to send pings. A TCP server to receive graceful quit
     * messages and file transfer requests. A TCP sender to send graceful quit
     * messages and file transfer requests.
     */
    private void initializeThreads() {
        // Initiate Ping Server
        this.pingServer = new PingServerUDP(this);
        this.pingServer.start();

        // 0 indicates the first successor ping sending thread.
        this.pingSenderFirst = new PingSenderUDP(this, true);
        this.pingSenderFirst.start();

        // 1 indicates the second successor ping sending thread.
        this.pingSenderSecond = new PingSenderUDP(this, false);
        this.pingSenderSecond.start();

        // Initiate TCP Server
        this.tcpServer = new TCPServer(this);
        this.tcpServer.start();
    }

    /**
     * Updates the predecessors of the peer based on id. If first is true, then
     * update first predecessor. Else update the second predecessor.
     * 
     * @param id   represents the id of the new predecessor.
     * @param flag determines which predecessor to update.
     */
    public void updatePredecessors(int id, int first) {
        if (first == 1) {
            setFirstPredecessor(id);
        } else {
            setSecondPredecessor(id);
        }
    }

    // =================OTHER UTILITY
    // FUNCTIONS======================================//

    /**
     * Parses the user input and directs decision to either the graceful quit
     * function or the file request function.
     * 
     * @param usr_input
     */
    private void parseUserInput(String usr_input) {

        // Draw up some simple regex for parsing input.
        String file_request_pattern_str = "request \\d{4}";
        String quit_pattern_str = "quit";
        String print_debug = "debug";

        Pattern file_request_pattern = Pattern.compile(file_request_pattern_str);
        Pattern quit_pattern = Pattern.compile(quit_pattern_str);
        Pattern debug_pattern = Pattern.compile(print_debug);

        Matcher file_matcher = file_request_pattern.matcher(usr_input);
        Matcher quit_matcher = quit_pattern.matcher(usr_input);
        Matcher debug_matcher = debug_pattern.matcher(usr_input);

        // FILE REQUEST INPUT MATCH
        if (file_matcher.find()) {
            // Grab the second element from the string split (the 4 numbers)
            int file_name = Integer.parseInt(usr_input.split(" ")[1]);

            // Edge case for when peer requests a file of the same hash as its peer id,
            // don't initiate any sending.
            if (file_name % 256 == this.getPeer()) {
                System.out.println("File is already stored at this peer!");
                return;
            }

            // Initiate file request procedure.
            System.out.println("File request message for " + file_name + " has been sent to my successor.");
            fileRequest(file_name, this.getPeer());

            // QUIT REQUEST INPUT MATCH
        } else if (quit_matcher.find()) {

            // Kill the ping sender and ping server.
            this.pingSenderFirst.shutdown();
            this.pingSenderSecond.shutdown();
            this.pingServer.shutdown();

            // Send messages TCP messages that we are leaving the network.
            this.gracefulQuit(this.getFirstPredecessor());
            this.gracefulQuit(this.getSecondPredecessor());

            this.shutdown = true;

            // MATCH FOR DEBUGGING
        } else if (debug_matcher.find()) {
            // Used to debug state information.
            System.out.println(String.format("[P2: %s P1: %s S1: %s S2: %s]", this.second_pred, this.first_pred,
                    this.first_succ, this.second_succ));
        }
    }

    /**
     * Initiates a file request procedure for a file with given filename.
     * 
     * @param hash hashed value of the filename.
     */
    public void fileRequest(int file_name, int sending_peer) {
        try {
            // Set up the TCP Socket
            Socket sendSocket = new Socket("localhost", cdht.getPort(this.getFirstSuccessor()));
            DataOutputStream messageStream = new DataOutputStream(sendSocket.getOutputStream());

            // Send the request message to the first successor. Third parameter = 1 => it is
            // a query.
            String file_request_msg = createFileRequest(file_name, sending_peer, 1);
            messageStream.writeBytes(file_request_msg);
            sendSocket.close();
        } catch (IOException e) {
            e.printStackTrace();
            return;
        }
    }

    public void initiateFileTransfer(int sending_peer, int file_name) {
        FileSenderUDP fs = new FileSenderUDP(file_name, sending_peer, this.MSS, this.drop_prob, this.time);
        fs.start();
    }

    public static String write_log_text(String event, long time, int seq_num, int num_bytes, int ack_num) {
        return String.format("%-10s %-10s %-10s %-10s %-10s", event, time, seq_num, num_bytes, ack_num);
    }

    /**
     * Initiates a graceful quit procedure for this peer to the peer with ID
     * receiver.
     * 
     * @param receiver
     */
    private void gracefulQuit(int receiver) {
        try {
            // Set up the TCP Socket
            Socket sendSocket = new Socket("localhost", cdht.getPort(receiver));
            DataOutputStream messageStream = new DataOutputStream(sendSocket.getOutputStream());

            String quitMessage = null;
            if (receiver == this.first_pred) {
                // The first predecessor's successors become the quitting peer's two successors.
                quitMessage = createGracefulQuitMessage(this.first_succ, this.second_succ);
            } else if (receiver == this.second_pred) {
                // The second predecessor's successors become the quitting peer's first
                // predecessor and first successor.
                quitMessage = createGracefulQuitMessage(this.first_pred, this.first_succ);
            } else {
                System.out.println("Impossible Error just occurred.");
                System.exit(1);
            }
            messageStream.writeBytes(quitMessage + "\n");
            sendSocket.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Handles when a peer has failed to return MAX_FAILS pings and is assumed dead.
     * We have to update successors accordingly.
     * 
     * @param first flag for whether the failed peer is a first successor or not.
     */
    public void handleDeadPeer(boolean first) {
        if (first) {
            // Print messages to stdout.
            System.out.println(String.format("Peer %d is no longer alive", this.getFirstSuccessor()));
            System.out.println(String.format("My first successor is now peer %d.", this.getSecondSuccessor()));

            // Set the first successor as the second successor if the first successor died.
            this.setFirstSuccessor(this.getSecondSuccessor());

        } else {
            // Wait a brief amount of time for the first successor to update their successors.
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

            // Print messages to stdout.
            System.out.println(String.format("Peer %d is no longer alive.", this.getSecondSuccessor()));
            System.out.println(String.format("My first successor is now peer %d.", this.getFirstSuccessor()));
        }

        try {
            // Create a TCP Socket to send message to new first successor.
            Socket sendSocket = new Socket("localhost", cdht.getPort(this.getFirstSuccessor()));
            DataOutputStream messageStream = new DataOutputStream(sendSocket.getOutputStream());
            // Create the TCP Message and send it.
            String msg = createSuccessorQuery();
            messageStream.writeBytes(msg);
            sendSocket.close();
        } catch (IOException e) {
            e.printStackTrace();
            return;
        }
    }

    //================TCP PROTOCOL MESSAGE FORMAT=============================//
    /*
     * [QUERY TYPE] [SENDING PEER ID] [PAYLOAD FIELD 1] [PAYLOAD FIELD 2] [PAYLOAD FIELD 3]
     * QUERY TYPE: {GQ: 'Graceful Quit', "FR": 'File Request'}
     * SENDING PEER ID: {The id of the sender}
     * PAYLOAD: {GQ: '[receivers nbew SUCC1] [receivers new SUCC2] [QUIT FLAG = 1 => if this peer wants to quit]', 
     *           FR: '[FILE NAME] [FLAG => true if successor has the file.] [QUERY FLAG = 1 if query]',
     *           DP: '[QUERY FLAG] [IF FLAG = 0: ID OF SUCCESSOR, ELSE 0] [0]'
     *          } 
     */

    //================TCP MESSAGE FUNCTIONS==================================//
    
    /**
     * Creates a file request message for the desired filename.
     * 
     * @param file_name integer name of the file.
     * @param sending_peer id of the peer who sent the query.
     * @param query flag for telling whether the message is a query or response.
     * @return
     */
    private String createFileRequest(int file_name, int sending_peer, int query) {
        // Computes the hash of the filename.
        int hash = file_name % 256;
        // Checks if the successor has the file.
        boolean has_file = successorHasFile(hash);
        int val = has_file ? 1 : 0;
        // Constructs the TCP message in format above.
        return "FR " + sending_peer + " " + file_name + " " + val + " " + query;
    }

    /**
     * Creates a TCP protocol message for graceful quitting. 
     * [GQ] [sending_peer] [first_id] [second_id] [query_flag = 1 => I want to leave]
     * @param first_id new first successor
     * @param second_id new second successor.
     * @return
     */
    private String createGracefulQuitMessage(int first_id, int second_id) {
        String payload = Integer.toString(first_id) + " " + Integer.toString(second_id) + " " + 1;
        return TCPmessageBeginning("GQ") + " " + payload;
    }

    /**
     * Creates a dead peer TCP message as either a query or a response.
     * @param flag if flag = true it is a first successor.
     * @return
     */
    private String createSuccessorQuery() {
        // The third field of the TCP message is a "1" because it is a query.
        // The 4th field of the TCP message is a "0" because queries don't know successors.
        return TCPmessageBeginning("DP")+ " " + 1 + " " + 0 + " " + 0;
    }
    
    /**
     * Helper method for producing the beginning of a TCP message header.
     * @param type
     * @return First two fields of a TCP message.
     */
    private String TCPmessageBeginning(String type) {
        return type + " " + Integer.toString(this.peer_id);
    }

    // =====================STATIC HELPER FUNCTIONS===========================//

    /**
     * Returns UDP Port for given peer_id
     * @param peer_id
     * @return port number for that peer_id
     */
    public static int getPort(int peer_id) {
        return DEFAULT_PORT + peer_id;
    }

    /**
     * Returns true if this peer's successor has the file. 
     * @param hash
     * @return boolean.
     */
    public boolean successorHasFile(int hash) {
        /**
         * Three cases.
         * 
         * Case 0: E.g peer = 1 hash = 3 successor = 3. => peer 3 owns it.
         * Case 1: E.g peer = 1 hash = 2 successor = 3. => peer 3 owns it.
         * Case 2: E.g peer = 15 hash = 220 successor = 1. => peer 1 owns it.
         * Case 3: E.g peer = 15 hash = 0 successor = 1. => peer 1 owns it.
         */
        return (this.getPeer() < this.getFirstSuccessor() && hash == this.getFirstSuccessor() || 
                this.getPeer() < this.getFirstSuccessor() && hash < this.getFirstSuccessor() && hash > this.getPeer() ||
                this.getPeer() > this.getFirstSuccessor() && hash > this.getPeer() && hash > this.getFirstSuccessor() ||
                this.getPeer() > this.getFirstSuccessor() && hash < this.getPeer() && hash < this.getFirstSuccessor());
    }

    //========================GETTER METHODS===============================//
    
    /**
     * Gets the first successor of the peer to the given id.
     * @return
     */
    public int getFirstSuccessor() {
        return this.first_succ;
    }

    /**
     * Gets the second successor of the peer to the given id.
     * @return
     */
    public int getSecondSuccessor() {
        return this.second_succ;
    }

    /**
     * Gets the first successor of the peer to the given id.
     * @return
     */
    public int getFirstPredecessor() {
        return this.first_pred;
    }

    /**
     * Gets the second successor of the peer to the given id.
     * @return
     */
    public int getSecondPredecessor() {
        return this.second_pred;
    }
    
    /**
     * Gets the peer id of the peer.
     * @return
     */
    public int getPeer() {
        return this.peer_id;
    }

    /**
     * Gets the MSS of the peer.
     * @return
     */
    public int getMSS() {
        return this.MSS;
    }

    //=======================SETTER METHODS============================//

    /**
     * Sets the first predecessor of the peer to the given id.
     * @param id
     */
    public void setFirstPredecessor(int id) {
        this.first_pred = id;
    }

    /**
     * Sets the second predecessor of the peer to the given id.
     * @param id
     */
    public void setSecondPredecessor(int id) {
        this.second_pred = id;
    }

    /**
     * Sets the first successor of the peer to the given id.
     * @param id
     */
    public void setFirstSuccessor(int id) {
        this.first_succ = id;
    }

    /**
     * Sets the second successor of the peer to the given id.
     * @param id
     */
    public void setSecondSuccessor(int id) {
        this.second_succ = id;
    }

}
