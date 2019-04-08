import java.net.*;
import java.io.*;
import java.util.regex.*;

import javax.jws.soap.SOAPBinding.Use;

public class cdht {

    public static final int DEFAULT_PORT = 50000;
    public static final int PING_FREQ = 20000;
    public static final int SOCKET_TIMEOUT_FREQ = 5000;
    public static final int MAX_FAILS = 4;

    public int peer_id;
    public int first_succ_id;
    public int second_succ_id;
    public int first_predecessor = -1;
    public int second_predecessor = -1;

    private int MSS;
    private float drop_prob;
    private PingServerUDP pingServer;
    private PingSenderUDP pingSenderFirst;
    private PingSenderUDP pingSenderSecond;
    private TCPServer tcpServer;

    public cdht(int peer_id, int first_succ_id, int second_succ_id, int MSS, float drop_prob) {
        this.peer_id = peer_id;
        this.first_succ_id = first_succ_id;
        this.second_succ_id = second_succ_id;
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

        while (true) {
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
        this.pingServer = new PingServerUDP(this);
        this.pingServer.start();
        this.pingSenderFirst = new PingSenderUDP(peer_id, first_succ_id);
        this.pingSenderFirst.start();
        this.pingSenderSecond = new PingSenderUDP(peer_id, second_succ_id);
        this.pingSenderSecond.start();
        this.tcpServer = new TCPServer(this);
        this.tcpServer.start();
    }

    /**
     * Updates the predecessors of the peer based on id.
     * 
     * @param id
     */
    public void updatePredecessors(int id) {
        if (this.first_predecessor == -1) {
            this.first_predecessor = id;
        } else if (this.second_predecessor == -1) {
            this.second_predecessor = id;
        }

        /*
         * Logic to get the first predecessor and second predecessor right when updating
         * predecessors. The predecessors are stored increasing order modulo number of
         * peers. I.e in the order (from least to greatest) 1 2 3 .. n 1 2 3 .. n ...
         */
        if (this.first_predecessor > this.peer_id && this.second_predecessor < this.peer_id) {
            int temp = this.first_predecessor;
            this.first_predecessor = this.second_predecessor;
            this.second_predecessor = temp;
        } else if (this.first_predecessor > this.peer_id && this.second_predecessor > this.peer_id) {
            if (this.first_predecessor < this.second_predecessor) {
                int temp = this.second_predecessor;
                this.second_predecessor = this.first_predecessor;
                this.first_predecessor = temp;
            }
        } else if (this.first_predecessor < this.peer_id && this.second_predecessor < this.peer_id) {
            if (this.first_predecessor < this.second_predecessor) {
                int temp = this.second_predecessor;
                this.second_predecessor = this.first_predecessor;
                this.first_predecessor = temp;
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

        Pattern file_request_pattern = Pattern.compile(file_request_pattern_str);
        Pattern quit_pattern = Pattern.compile(quit_pattern_str);

        Matcher file_matcher = file_request_pattern.matcher(usr_input);
        Matcher quit_matcher = quit_pattern.matcher(usr_input);

        if (file_matcher.find()) {
            // Grab the second element from the string split (the 4 numbers)
            String file_name = usr_input.split(" ")[1];

            createFileRequest(file_name);
        } else if (quit_matcher.find()) {
            gracefulQuit();
        }
    }

    /**
     * Initiates a graceful quit procedure for this peer.
     */
    private void gracefulQuit() {
        try {
            Socket sendSocket = new Socket("localhost", cdht.getPort(2));
            DataOutputStream messageStream = new DataOutputStream(sendSocket.getOutputStream());
            String test = "HELLO PEER 2";
            messageStream.writeBytes(test + "\n");
            sendSocket.close();

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Creates a file request for the desired user input string.
     * @param usr_input
     */
    private void createFileRequest(String usr_input) {
        System.out.println("File request for " + usr_input);
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
        return (this.first_predecessor == id);
    }

    /**
     * Checks if the id is the second predecessor of the peer.
     * @param id
     * @return True if the id is the second predecessor.
     */
    public boolean isSecondPredecessor(int id) {
        return (this.second_predecessor == id);
    }

    /**
     * Sets the first predecessor of the peer to the given id.
     * @param id
     */
    public void setFirstPredecessor(int id) {
        this.first_predecessor = id;
    }

    /**
     * Sets the second predecessor of the peer to the given id.
     * @param id
     */
    public void setSecondPredecessor(int id) {
        this.second_predecessor = id;
    }

}
