import java.net.*;
import java.io.*;
import java.util.regex.*;

import javax.jws.soap.SOAPBinding.Use;

public class cdht {

    public static final int DEFAULT_PORT = 50000;
    public static final int PING_FREQ = 20000;
    public static final int SOCKET_TIMEOUT_FREQ = 5000;
    public static final int MAX_FAILS = 4;

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

    private void killThreads() throws IOException {
        this.pingServer.shutdown();
        this.pingSenderFirst.shutdown();
        this.pingSenderSecond.shutdown();
        this.tcpServer.shutdown();
        this.shutdown = true;
    }

    /**
     * Updates the predecessors of the peer based on id.
     * 
     * @param id
     */
    public void updatePredecessors(int id) {

        if (isPredecessor(id)) {
            // Potentially due to the scheduling of the threads, we could receive two ping requests in a row
            // From the same peer. In that case we should just disregard the second one.
            return;
        }

        if (this.first_pred == -1) {
            this.first_pred = id;
        } else if (this.second_pred == -1) {
            this.second_pred = id;
        }

        /*
        * Logic to get the first predecessor and second predecessor right when updating
        * predecessors. The predecessors are stored increasing order modulo number of
        * peers. I.e in the order (from least to greatest) 1 2 3 .. n 1 2 3 .. n ...
        */
        
        if (this.first_pred > this.peer_id && this.second_pred < this.peer_id) {
            int temp = this.first_pred;
            this.first_pred = this.second_pred;
            this.second_pred = temp;
        } else if (this.first_pred > this.peer_id && this.second_pred > this.peer_id) {
            if (this.first_pred < this.second_pred) {
                int temp = this.second_pred;
                this.second_pred = this.first_pred;
                this.first_pred = temp;
            }
        } else if (this.first_pred < this.peer_id && this.second_pred < this.peer_id) {
            if (this.first_pred < this.second_pred) {
                int temp = this.second_pred;
                this.second_pred = this.first_pred;
                this.first_pred = temp;
            }
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

        if (file_matcher.find()) {
            // Grab the second element from the string split (the 4 numbers)
            String file_name = usr_input.split(" ")[1];
            createFileRequest(file_name);
        } else if (quit_matcher.find()) {
            gracefulQuit(this.first_pred);
            gracefulQuit(this.second_pred);
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
                quitMessage = createQuitMessage(this.first_succ, this.second_succ);
            } else if (receiver == this.second_pred) {
                // The second predecessor's successors become the quitting peer's first predecessor and first successor.
                quitMessage = createQuitMessage(this.first_pred, this.first_succ);
            } else {
                System.out.println("Impossible Error just occurred.");
                System.exit(1);
            }
            messageStream.writeBytes(quitMessage + "\n");
            sendSocket.close();
            killThreads();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    //================TCP PROTOCOL MESSAGE FORMAT=============================//
    /*
     * [QUERY TYPE] [SENDING PEER ID] [PAYLOAD]
     * QUERY TYPE: {GQ: 'Graceful Quit', "FR": 'File Request'}
     * SENDING PEER ID: {The id of the sender}
     * PAYLOAD: {GQ: '[ID to be set as receivers FIRST SUCC] [ID to be set as receivers SECOND SUCC', 
     *           FR: '[FILENAME]'
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
    private String createQuitMessage(int first_id, int second_id) {
        String type = "GQ";
        String from = Integer.toString(this.peer_id);
        String payload = Integer.toString(first_id) + " " + Integer.toString(second_id);
        return type + " " + from + " " + payload;
    }

    // ================GETTER AND SETTER HELPER FUNCTIONS===========================//

    /**
     * Returns UDP Port for given peer_id
     * @param peer_id
     * @return port number for that peer_id
     */
    public static int getPort(int peer_id) {
        return DEFAULT_PORT + peer_id;
    }

    /**
     * Checks if the given id is a predecessor of the peer.
     * @param id
     * @return True if a predecesssor.
     */
    public boolean isPredecessor(int id) {
        return (isFirstPredecessor(id) || isSecondPredecessor(id));
    }

    /**
     * Checks if the given id is the first predecessor of the peer
     * @param id
     * @return True if the id is the first predecessor.
     */
    public boolean isFirstPredecessor(int id) {
        return (this.first_pred == id);
    }

    /**
     * Checks if the id is the second predecessor of the peer.
     * @param id
     * @return True if the id is the second predecessor.
     */
    public boolean isSecondPredecessor(int id) {
        return (this.second_pred == id);
    }

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
     * Gets the peer id of the peer.
     * @param id
     */
    public int getPeer() {
        return this.peer_id;
    }

}
