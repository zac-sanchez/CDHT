import java.net.*;
import java.io.*;

public class PingSenderUDP implements Runnable {
    private static final String threadName = "PingSenderUDP";
    private Thread t;
    private cdht peer;
    private boolean first;
    private volatile boolean shutdown = false;
    private DatagramSocket udpSocket;

    /**
     * Instantiates a PingSender to send pings over UDP.
     * @param peer
     * @param first - if first then we send to the first successor. Otherwise send to the second successor.
     */
    public PingSenderUDP(cdht peer, boolean first) {
        this.peer = peer;
        this.first = first;
    }
    
    /**
     * Runs the main thread loop.
     */
    public void run() {
        try {
            // Create a socket and set a maximum time (TIMEOUT) for which the receiver must send a response.
            this.udpSocket = new DatagramSocket();
            this.udpSocket.setSoTimeout(cdht.SOCKET_TIMEOUT_FREQ);
            while(!shutdown) {
                sendPing(this.first);;
            }
        } catch (SocketException e) {
            return;
        }
    }

    /**
     *  Starts the thread.
     */
    public void start () {
        if (this.t == null) {
            this.t = new Thread (this, threadName);
            this.t.start();
        }
    }

    /**
     * Shuts down the thread.
     */
    public void shutdown() {
        this.udpSocket.close();
        this.shutdown = true;
    }

    /**
     * Loop that continually sends pings based on a timer.
     * @param id
     */
    private void sendPing(boolean first) {
        // Set the ip to just a local address.
        try {
            InetAddress ip = InetAddress.getLocalHost();
        
            // Stores how many failed pings we have. Once we go over the fail threshold, declare the successor dead.
            int ping_fails = 0;
            int id;
        
            while(!this.shutdown) {
                if (first) {
                    id = this.peer.getFirstSuccessor();
                } else {
                    id = this.peer.getSecondSuccessor();
                }
                // Create a bytestream from a ping request to send.
                byte[] ping_buf;
                String ping_string = createPingRequest(peer.getPeer(), this.first);
                ping_buf = ping_string.getBytes();
                DatagramPacket ping_request = new DatagramPacket(ping_buf,  ping_buf.length, ip, cdht.getPort(id));
                
                // Send the request.
                System.out.println("Sending ping request to Peer " + id);
                this.udpSocket.send(ping_request);

                // buffer to store the response in.
                byte[] ping_response = new byte[1024];
                
                try {
                    // Response correctly recevied, read response and print out the response to terminal.
                    DatagramPacket response_packet = new DatagramPacket(ping_response, ping_response.length);
                    this.udpSocket.receive(response_packet);
                    
                    // Reset the failure counter as we have correctly received a ping.
                    ping_fails = 0;
                    printPingResponse(response_packet);
                    
                } catch (SocketTimeoutException e) {
                    // No response has been received. Incremement the # of fails, or deal with dead peers.
                    if (ping_fails >= cdht.MAX_FAILS) {
                        peer.handleDeadPeer(this.first);
                    } else {
                        ping_fails++;
                    }
                }
                // Wait a while until sending the next set of pings.
                Thread.sleep(cdht.PING_FREQ);
            }
        } catch (IOException e) {
            return;
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    /**
     * Creates a ping request string giving information about the sending peer to the successor. 
     * 
     * Ping format is: [PG] [SENDING PEER]  [FLAG]
     * 
     * Where flag == true => we are sending to the first successor.
     * 
     * @param cdht peer
     * @return A ping request string consisting of the peer id.
     */
    private String createPingRequest(int peer_id, boolean flag) {
        int val = flag ? 1 : 0;
        return "PG" + " " + peer_id + " " + val;
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
