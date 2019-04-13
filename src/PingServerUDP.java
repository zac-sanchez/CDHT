import java.io.*;
import java.net.*;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;

public class PingServerUDP implements Runnable {

    private static final String threadName = "PingServerUDP";
    private Thread t;
    private cdht peer;
    private DatagramSocket udpSocket;
    private FileOutputStream fos = null;
    private volatile boolean shutdown = false;
    private PrintWriter requesting;

    /**
     * Instantiates a ping server.
     * 
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
            this.udpSocket = new DatagramSocket(cdht.getPort(peer.getPeer()));
            while (!this.shutdown) {
                // Read in a request through the socket.
                DatagramPacket request = new DatagramPacket(new byte[peer.getMSS() + cdht.TRANSFER_HEADER_LEN],
                        peer.getMSS() + cdht.TRANSFER_HEADER_LEN);
                this.udpSocket.receive(request);

                // Handles the UDP packet based on whether it is a ping or a file send.
                handlePacket(request);
            }
        } catch (SocketException e) {
            return;
        } catch (IOException e) {
            return;
        }
    }

    /**
     * Starts the main thread.
     */
    public void start() {
        if (this.t == null) {
            this.t = new Thread(this, threadName);
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
     * Directs packet function based on whether the packet was a file request or a
     * ping.
     * 
     * @param request
     */
    private void handlePacket(DatagramPacket request) {

        ByteArrayOutputStream request_outstrm = new ByteArrayOutputStream();
        request_outstrm.write(request.getData(), 0, 2);
        byte[] type_buf = request_outstrm.toByteArray();
        try{ 
            if (new String(type_buf).equals("FS")) {

                // Grab header data from the request packet.
                request_outstrm = new ByteArrayOutputStream();
                request_outstrm.write(request.getData(), 0, cdht.TRANSFER_HEADER_LEN);
                byte[] header_buf = request_outstrm.toByteArray();
                String header = new String(header_buf);
                String[] header_data = header.trim().split(" ");

                int seq_num = Integer.parseInt(header_data[1]);
                int num_bytes_sent = Integer.parseInt(header_data[2]);
                int eof_flag = Integer.parseInt(header_data[3]);

                // Grab ip and port information from sending peer.
                InetAddress ip = request.getAddress();
                int port = request.getPort();

                if (this.fos == null) {
                    this.fos = new FileOutputStream("received_file.pdf");
                    this.requesting = new PrintWriter("requesting_log.txt");
                }
                receiveFilePacket(request, eof_flag);
                Duration time_diff = Duration.between(peer.time, Instant.now());
                this.requesting.println(cdht.write_log_text("rcv", time_diff.toMillis(), 
                                                            seq_num, num_bytes_sent, 0));
                ackFilePacket(seq_num, num_bytes_sent, ip, port);
                this.requesting.println(cdht.write_log_text("snd", time_diff.toMillis(), 
                                                            0, num_bytes_sent, seq_num+num_bytes_sent));
            } else if (new String(type_buf).equals("PG")) {
                // Print ping request and send a response back to the sender.
                printPingRequest(request);
                sendPingResponse(this.udpSocket, request, Integer.toString(peer.getPeer()));
            }
        } catch (FileNotFoundException e) {
            return;
        }
        
    }

    private void receiveFilePacket(DatagramPacket request, int eof_flag) {
        try {
            this.fos.write(request.getData(), cdht.TRANSFER_HEADER_LEN, request.getLength() - cdht.TRANSFER_HEADER_LEN);

            if (eof_flag == 1) {
                System.out.println("The file is received.");
                this.fos.close();
                this.requesting.close();
                this.fos = null;
            }
        } catch (IOException e) {
            return;
        }
    }

    /**
     * UDP ACK FORMAT: [ACK] [ACK_NUM]
     * 
     * @param sending_peer who to send the ACK to.
     * @param num_bytes_sent how many bytes were read (used for sequence numbers).
     */
    private void ackFilePacket(int seq_num, int num_bytes_sent, InetAddress ip, int port) {
        String ack = "ACK" + " " + seq_num + " " + num_bytes_sent;
        byte[] ack_bytes = ack.getBytes();
        DatagramPacket ack_pkt = new DatagramPacket(ack_bytes, ack_bytes.length, ip, port);
        try {
            this.udpSocket.send(ack_pkt);
        } catch (IOException e) {
            e.printStackTrace();
            return;
        }
    }

    /**
     * Prints a ping receipt to standard output and updates predecessors.
     * 
     * @param request
     */
    private void printPingRequest(DatagramPacket request) {
        // Read the ping data into an array.
        byte[] buf = request.getData();
        BufferedReader br = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(buf)));
        try {
            String[] ping_str_data = br.readLine().trim().split(" ");
            // Convert ping data to an integer array
            int[] ping_data = new int[2];
            ping_data[0] = Integer.parseInt(ping_str_data[1]);
            ping_data[1] = Integer.parseInt(ping_str_data[2]);
            printPingReceipt(ping_data[0]);
            peer.updatePredecessors(ping_data[0], ping_data[1]);
        } catch (NumberFormatException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Prints out a receive message for a ping.
     * 
     * @param id
     */
    private void printPingReceipt(int id) {
        System.out.println("A ping request message was received from Peer " + id);
    }

    /**
     * Sends a ping response based on received packet String.
     * 
     * @param socket UDP socket.
     * @param request request packet.
     * @param ping_text text from the request packet.
     * @throws IOException
     */
    private void sendPingResponse(DatagramSocket socket, DatagramPacket request, String ping_text) {
        byte[] ping_response = ping_text.getBytes();
        DatagramPacket response = new DatagramPacket(ping_response, ping_response.length, request.getAddress(), 
                                                    request.getPort());
        try {
            socket.send(response);
        } catch (IOException e) {
            e.printStackTrace();
            return;
        }
    }
}
