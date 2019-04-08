import java.util.*;
import java.net.*;
import java.io.*;
import java.time.LocalDateTime;

public class PingSenderUDP implements Runnable {
    private static final String threadName = "PingSenderUDP";
    private Thread t;
    private int sender_id;
    private int receiver_id;

    /**
     * Instantiates a PingSender to send pings over UDP.
     * @param sender_id
     * @param receiver_id
     */
    public PingSenderUDP(int sender_id, int receiver_id) {
        this.sender_id = sender_id;
        this.receiver_id = receiver_id;
    }
    
    /**
     * Runs the main thread loop.
     */
    public void run() {
        try {
            pingLoop(this.receiver_id);
        } catch (IOException e) {
            System.err.println(e);
            System.exit(1);
        }
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

    /**
     * Loop that continually sends pings based on a timer.
     * @param id
     * @throws IOException
     */
    private void pingLoop(int id) throws IOException {
        // Set the ip to just a local address.
        InetAddress ip = InetAddress.getByName("localhost");

        // Stores how many failed pings we have. Once we go over the fail threshold, declare the successor dead.
        int ping_fails = 0;

        // Loop indefinitely (leave the ping sender on indefinitely)
        while(true) {
            
            // Create a socket and set a maximum time (TIMEOUT) for which the receiver must send a response.
            DatagramSocket socket = new DatagramSocket();
            socket.setSoTimeout(cdht.SOCKET_TIMEOUT_FREQ);
            
            // Create a bytestream from a ping request to send.
            byte[] ping_buf;
            String ping_string = createPingRequest(this.sender_id);
            ping_buf = ping_string.getBytes();
            DatagramPacket ping_request = new DatagramPacket(ping_buf,  ping_buf.length, ip, cdht.getPort(id));
            
            // Send the request.
            System.out.println("Sending ping request to Peer " + id);
            socket.send(ping_request);

            // Create a buffer to store the response in.
            byte[] ping_response = new byte[1024];
            try {
                // Response correctly recevied, read response and print out the response to terminal.
                DatagramPacket response_packet = new DatagramPacket(ping_response, ping_response.length);
                socket.receive(response_packet);
                ping_fails = 0;
                printPingResponse(response_packet);

            } catch (SocketTimeoutException e) {
                // No response has been received. Incremement the # of fails, or update sucessors as the peer is dead.
                if (ping_fails >= cdht.MAX_FAILS) {
                    System.out.println("Someone died. RIP.");
                } else {
                    ping_fails++;
                }
            }
            
            // Wait a while until sending the next set of pings.
            try {
                Thread.sleep(cdht.PING_FREQ);
            } catch (InterruptedException e) {
                System.out.println("Sleep interrupted.");
            }

            socket.close();
        }
    }

    /**
     * Creates a ping request string giving information about the sending peer.
     * @param peer_id
     * @return A ping request string consisting of the peer id.
     */
    private String createPingRequest(int peer_id) {
        return "" + peer_id;
    }

    /**
     * Prints out the message stored in a receive ping.
     * @param ping_response
     */
    private void printPingResponse(DatagramPacket ping_response) throws IOException{
        byte[] buf = ping_response.getData();
        BufferedReader br = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(buf)));
        String ping_text = br.readLine().replaceAll("\\s", "");
        System.out.println("A ping response message was received from Peer " + ping_text);
    }
}