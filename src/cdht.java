import java.net.*;
import java.io.*;
import java.util.regex.*;

public class cdht {

    public static final int DEFAULT_PORT = 50000;
    public static final int PING_FREQ = 20000;
    public static final int SOCKET_TIMEOUT_FREQ = 5000;
    public static final int MAX_FAILS = 2;

    private int peer_id;
    private int first_succ;
    private int second_succ;
    private int first_pred = -1;
    private int second_pred = -1;

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
    }

    /**
     * Reads in arguments and initialises threads.
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

    private void killThreads() {
        this.pingServer.shutdown();
        this.pingSenderFirst.shutdown();
        this.pingSenderSecond.shutdown();

        try {
            this.tcpServer.shutdown();
        } catch (IOException e) {
            e.printStackTrace();
            return;
        }
        this.shutdown = true;
    }

    /**
     * Updates the predecessors of the peer based on id. If first is true, then update first predecessor.
     * Else update the second predecessor.
     * 
     * @param id represents the id of the new predecessor.
     * @param flag determines which predecessor to update.
     */
    public void updatePredecessors(int id, int first) {
        if (first == 1) {
            setFirstPredecessor(id);
        } else {
            setSecondPredecessor(id);
        }
    }

    // =================OTHER UTILITY FUNCTIONS======================================//

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

        if (file_matcher.find()) {
            // Grab the second element from the string split (the 4 numbers)
            String file_name = usr_input.split(" ")[1];
            createFileRequest(file_name);
        } else if (quit_matcher.find()) {
            gracefulQuit(this.first_pred);
            gracefulQuit(this.second_pred);
            killThreads();
        } else if (debug_matcher.find()) {
            System.out.println(String.format("[P2: %s P1: %s S1: %s S2: %s]", this.second_pred, 
                                            this.first_pred, this.first_succ, this.second_succ));
        }
    }

    /**
     * Initiates a graceful quit procedure for this peer to the peer with ID receiver.
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
                // The second predecessor's successors become the quitting peer's first predecessor and first successor.
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
     * Handles when a peer has failed to return MAX_FAILS pings and is assumed dead. We have to update successors
     * accordingly.
     * 
     * @param first flag for whether the failed peer is a first successor or not.
     */
    public void handleDeadPeer(boolean first) {
        if (first) {

            // Print messages to stdout.
            System.out.println(String.format("Ping %d is no longer alive", getFirstSuccessor()));
            System.out.println(String.format("My first successor is now peer %d", getSecondSuccessor()));

            // Set the first successor as the second successor if the first successor died.
            setFirstSuccessor(getSecondSuccessor());
            
        } else {
            // Print messages to stdout.
            System.out.println(String.format("Ping %d is no longer alive", getSecondSuccessor()));
            System.out.println(String.format("My first successor is now peer %d", getFirstSuccessor()));
        }

        try {
            // Create a TCP Socket to send message to new first successor.
            Socket sendSocket = new Socket("localhost", cdht.getPort(getFirstSuccessor()));
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
     * [QUERY TYPE] [SENDING PEER ID] [PAYLOAD FIELD 1] [PAYLOAD FIELD 2]
     * QUERY TYPE: {GQ: 'Graceful Quit', "FR": 'File Request'}
     * SENDING PEER ID: {The id of the sender}
     * PAYLOAD: {GQ: '[ID to be set as receivers FIRST SUCC] [ID to be set as receivers SECOND SUCC]', 
     *           FR: '[FILENAME] [0]'
     *           DP: '[QUERY FLAG] [IF FLAG = 0: ID OF SUCCESSOR, ELSE 0]'
     *          } 
     */

    /**
     * Creates a file request for the desired user input string.
     * @param usr_input
     */

    private void createFileRequest(String usr_input) {
        System.out.println("File request for " + usr_input);
    }

    /**
     * Creates a TCP protocol meess
     * The 
     * @param first_id
     * @param second_id
     * @return
     */
    private String createGracefulQuitMessage(int first_id, int second_id) {
        String payload = Integer.toString(first_id) + " " + Integer.toString(second_id);
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
     
        return TCPmessageBeginning("DP")+ " " + 1 + " " + 0;
    }

    private String TCPmessageBeginning(String type) {
        return type + " " + Integer.toString(this.peer_id);
    }

    // =====================HELPER FUNCTIONS===========================//

    /**
     * Returns UDP Port for given peer_id
     * @param peer_id
     * @return port number for that peer_id
     */
    public static int getPort(int peer_id) {
        return DEFAULT_PORT + peer_id;
    }

    //========================GETTER METHODS===============================//
    
    /**
     * Gets the first successor of the peer to the given id.
     * @param id
     */
    public int getFirstSuccessor() {
        return this.first_succ;
    }

    /**
     * Gets the second successor of the peer to the given id.
     * @param id
     */
    public int getSecondSuccessor() {
        return this.second_succ;
    }

        /**
     * Gets the first successor of the peer to the given id.
     * @param id
     */
    public int getFirstPredecessor() {
        return this.first_pred;
    }

    /**
     * Gets the second successor of the peer to the given id.
     * @param id
     */
    public int getSecondPredecessor() {
        return this.second_pred;
    }
    
    /**
     * Gets the peer id of the peer.
     * @param id
     */
    public int getPeer() {
        return this.peer_id;
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
