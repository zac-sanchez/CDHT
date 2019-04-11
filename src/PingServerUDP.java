import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.*;

public class PingServerUDP implements Runnable {

    private static final String threadName = "PingServerUDP";
    private Thread t;
    private cdht peer;
    private DatagramSocket UDPSocket;
    private volatile boolean shutdown = false;

    /**
     * Instantiates a ping server.
     * @param peer
     */
    public PingServerUDP(cdht peer) {
        this.peer = peer;
    }

    /**
     * Main running loop for the thread.
     */
    public void run() {
        try {
            // Create a new UDP socket with the given port.
            this.UDPSocket = new DatagramSocket(cdht.getPort(peer.getPeer()));
            while(!shutdown) {
                try {
                    // Read in a request through the socket.
                    DatagramPacket request = new DatagramPacket(new byte[1024], 1024);
                    this.UDPSocket.receive(request);
                    try {
                        // Handles the ping request by printing receipt to stdout and updating peer's predecessors.
                       handlePingRequest(request);

                        // Send a response to the sender acknowledging the receipt.
                        sendPingResponse(this.UDPSocket, request, Integer.toString(peer.getPeer()));

                    } catch (IOException e) {
                        e.printStackTrace();
                        continue;
                    }
                } catch (IOException e){
                    e.printStackTrace();
                    continue;
                }

            }
        } catch (SocketException e) {
            return;
        }
    }

    /**
     * Starts the main thread.
     */
    public void start() {
        if (this.t == null) {
            this.t = new Thread (this, threadName);
            this.t.start ();
        }
    }

    /**
     * Shuts down the thread.
     */
    public void shutdown() {
        this.UDPSocket.close();
        this.shutdown = true;
    }

    /**
     * Prints out a receive message for a ping.
     * @param id
     */
    private void printPingReceipt(int id) {
        System.out.println("A ping request message was received from Peer " + id);
    }

    /**
     * Prints a ping receipt to standard output and updates predecessors.
     * 
     * @param request
     * @throws IOException (Should never happen)
     */
    private void handlePingRequest(DatagramPacket request) throws IOException{
        // Read the ping data into an array.
        byte[] buf = request.getData();
        BufferedReader br = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(buf)));
        String[] ping_str_data = br.readLine().trim().split(" ");

        // Convert ping data to an integer array
        int[] ping_data = new int[2];
        ping_data[0] = Integer.parseInt(ping_str_data[0]);
        ping_data[1] = Integer.parseInt(ping_str_data[1]);

        printPingReceipt(ping_data[0]);
        peer.updatePredecessors(ping_data[0], ping_data[1]);
    }

    /**
     * Sends a ping response based on received packet String.
     * 
     * @param socket UDP socket.
     * @param request request packet.
     * @param ping_text text from the request packet.
     * @throws IOException
     */
    private void sendPingResponse(DatagramSocket socket, DatagramPacket request, String ping_text) throws IOException {
        byte[] ping_response;
        ping_response = ping_text.getBytes();
        DatagramPacket response = new DatagramPacket(ping_response, ping_response.length, request.getAddress(), 
                                        request.getPort());
        socket.send(response);
    }

    /**
     * Prints a response stating that a message has been sent.
     * @param peer_id
     *
     * private static void printPingResponse(String peer_id) {
     *     System.out.println("Sending ping response to Peer " + peer_id);
     * }
     */
}
