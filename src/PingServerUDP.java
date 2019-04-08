import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.*;

public class PingServerUDP implements Runnable {

    private static final String threadName = "PingServerUDP";
    private Thread t;
    private cdht peer;
    private int peer_id;
    private DatagramSocket socket;

    /**
     * Instantiates a ping server.
     * @param peer
     */
    public PingServerUDP(cdht peer) {
        this.peer = peer;
        this.peer_id = peer.peer_id;
    }

    /**
     * Main running loop for the thread.
     */
    public void run() {
        try {
            // Create a new UDP socket with the given port.
            this.socket = new DatagramSocket(cdht.getPort(peer_id));
            while(true) {
                try {

                    // Read in a request through the socket.
                    DatagramPacket request = new DatagramPacket(new byte[1024], 1024);
                    this.socket.receive(request);
                    try {
                        // Prints receipt of request from sender.
                        String str_id = getPacketString(request);
                        int id = Integer.parseInt(str_id.trim());
                        printPingReceipt(id);
                        
                        // If the id has changed, then update the predecessors.
                        peer.updatePredecessors(id); 

                        // Send a response to the sender acknowledging the receipt.
                        sendPingResponse(this.socket, request, Integer.toString(peer_id));
                        printPingResponse(Integer.toString(id));
                    } catch (IOException e) {
                        System.out.println("Error reading ping.");
                        System.exit(1);
                    }
                } catch (IOException e){
                    System.err.println(e);
                    System.exit(1);
                }

            }
        } catch (SocketException e) {
            System.err.println(e);
            System.exit(1);
        }
    }

    /**
     * Starts the main thread.
     */
    public void start () {
        if (t == null) {
            t = new Thread (this, threadName);
            t.start ();
        }
    }

    /**
     * Prints out a receive message for a ping.
     * @param id
     */
    private void printPingReceipt(int id) {
        System.out.println("A ping request message was received from Peer " + id);
    }

    /**
     * Retrieves the string data sent from a request packet.
     * @param request
     * @return String of the request.
     * @throws IOException (Should never happen)
     */
    private String getPacketString(DatagramPacket request) throws IOException{
        byte[] buf = request.getData();
        BufferedReader br = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(buf)));
        return br.readLine();
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
     */
    private void printPingResponse(String peer_id) {
        System.out.println("Sending ping response to Peer " + peer_id);
    }
}
