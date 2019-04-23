import java.net.*;
import java.io.*;
import java.time.Instant;
import java.time.Duration;

public class FileSenderUDP implements Runnable {
    private static final String threadName = "PingSenderUDP";
    private static final int SOCKET_TIMEOUT = 1000;
    private Thread t;

    private int MSS;
    private int sending_peer;
    private float drop_prob;
    private Instant time;
    private File file;

    public FileSenderUDP(int file_name, int sending_peer, int MSS, float drop_prob, Instant start_time) {
        this.sending_peer = sending_peer;
        this.MSS = MSS;
        this.drop_prob = drop_prob;
        this.file = new File("./" + file_name + ".pdf");
        this.time = start_time;
    }

    public void run() {
        beginFileTransfer();
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

    private void beginFileTransfer() {
        System.out.println("We now start sending the file .....");
        try {
            // For writing transmission data to a log.
            PrintWriter sender_log = new PrintWriter("responding_log.txt");

            // Setup networking variables
            InetAddress ip = InetAddress.getLocalHost();
            int port = cdht.getPort(this.sending_peer);
            DatagramSocket socket = new DatagramSocket();
            socket.setSoTimeout(SOCKET_TIMEOUT);

            // Create buffer to send file data (dynamically allocated based on MSS size)
            byte[] send_buffer = null;
            // Create buffer to receive acks.
            byte[] rcv_buffer = new byte[cdht.TRANSFER_HEADER_LEN];
            // Create buffer for storing header data.
            byte[] header_data; 
            // Stores how much of the file has left to be read.
            long file_len = this.file.length();
            // Stores the current amount of data read for Sequence numbers.
            int seq_num = 1;
            // Flag for indicating whether we have reached the end of the file.
            int eof_flag = 0;
            // Flag for indicating whether we are retransmitting a packet.
            int retrans_flag = 0;

            // Get a ByteStream from the File.
            ByteArrayInputStream header_data_stream;
            BufferedInputStream file_data_stream = new BufferedInputStream(new FileInputStream(this.file));
            
            long size = MSS;
            // Loop until the file length is zero or until we have finished retransmitting a lost packet.
            while (file_len > 0 || retrans_flag == 1) {
                Duration time_diff = Duration.between(this.time, Instant.now());
                // If the packet is not a retransmission, do not write any extra data to the buffer.
                if (retrans_flag == 0) {
                    
                    // Send MSS bytes of data if the filesize is large enough, otherwise send the remainder of the file.
                    if (file_len < MSS) {
                        size = file_len;
                        eof_flag = 1;
                    } else {
                        size = MSS;
                    }

                    // Create packet header
                    header_data = createPacketHeader(seq_num, (int) size, eof_flag);
                    // Time between end of program and now.
                    sender_log.println(cdht.write_log_text("snd", time_diff.toMillis(), seq_num, (int) size, 0));

                    // Reduce file size by packet size.
                    file_len -= size;
                    // Increase the amount of file data read to be sent in the packet header.
                    seq_num += size;

                    // Create a byte array input stream for the packet header.
                    header_data_stream = new ByteArrayInputStream(header_data);

                    // Read in the header to first TRANSFER_HEADER_LEN bytes then read in the rest from the file stream.
                    send_buffer = new byte[cdht.TRANSFER_HEADER_LEN + (int) size];
                    header_data_stream.read(send_buffer, 0, cdht.TRANSFER_HEADER_LEN);
                    file_data_stream.read(send_buffer, cdht.TRANSFER_HEADER_LEN, (int) size);
                    header_data_stream.close();
                } else {
                    sender_log.println(cdht.write_log_text("RTX", time_diff.toMillis(), seq_num, (int) size, 0));
                }

                // Randomly drop the packet, otherwise send it.
                double rand = Math.random();
                if (rand > this.drop_prob) {
                    retrans_flag = 0;
                    DatagramPacket pkt = new DatagramPacket(send_buffer, send_buffer.length, ip, port);
                    socket.send(pkt);
                } else {
                    if (retrans_flag == 1) {
                        sender_log.println(cdht.write_log_text("RTX/Drop", time_diff.toMillis(), seq_num, (int) size, 0));
                    } else {
                        sender_log.println(cdht.write_log_text("Drop", time_diff.toMillis(), seq_num, (int) size, 0));
                    }
                }
               
                try {
                    // Wait for an acknowledgement packet from the receiver.
                    DatagramPacket ack_packet = new DatagramPacket(rcv_buffer, rcv_buffer.length);
                    socket.receive(ack_packet);
                    String ack_response = new String(ack_packet.getData());
                    String[] ack_data = ack_response.trim().split(" ");
                    
                    // Reading in num_bytes_sent and ack number from receivers ack.
                    int num_bytes_sent = Integer.parseInt(ack_data[2]);
                    int ack_num = Integer.parseInt(ack_data[1]);

                    // If we did not receive an "ACK" Continue waiting for an ack.
                    while (!ack_data[0].equals("ACK")) {
                        socket.receive(ack_packet);
                        ack_response = new String(ack_packet.getData());
                        ack_data = ack_response.trim().split(" ");
                    }
                    sender_log.println(cdht.write_log_text("rcv", time_diff.toMillis(), 0, num_bytes_sent, ack_num));
                } catch (SocketTimeoutException e) {
                    // On timeout set the retransmission flag so we know to retransmit.
                    retrans_flag = 1;
                }
            }
            // Close all streams and the UDP socket.
            System.out.println("The file is sent.");
            file_data_stream.close();
            sender_log.close();
            socket.close();
        } catch (UnknownHostException e1) {
            return;
        } catch (FileNotFoundException e) {
            e.printStackTrace();
            return;
        } catch (IOException e) {
            e.printStackTrace();
            return;
        }
    }

    /**
     * Packet header format:
     * 
     * [UDP MSG TYPE=FS] [SEQ_NUM] [NUM_SENT_BYTES (Sent Bytes)] [EOF = 0 => not the end of file.]
     * @param curr_len
     * @return byte array for the header.
     */
    private byte[] createPacketHeader(int seq_num, int num_bytes_sent, int end_of_file) {
        String header = "FS " + seq_num + " " + num_bytes_sent + " " + end_of_file;
        ByteArrayInputStream bais = new ByteArrayInputStream(header.getBytes());
        byte[] header_buf = new byte[cdht.TRANSFER_HEADER_LEN];
        bais.read(header_buf, 0, cdht.TRANSFER_HEADER_LEN);
        return header_buf;
    }
    
}