import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;

public class FileSenderUDP implements Runnable {
    private static final String threadName = "PingSenderUDP";
    private static final int SOCKET_TIMEOUT = 1000;
    private Thread t;

    private int MSS;
    private int sending_peer;
    private int peer_id;
    private float drop_prob;
    private File file;

    public FileSenderUDP(int file_name, int sending_peer, int peer_id, int MSS, float drop_prob) {
        this.sending_peer = sending_peer;
        this.peer_id = peer_id;
        this.MSS = MSS;
        this.drop_prob = drop_prob;
        this.file = new File("./" + file_name + ".pdf");
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
        try {
            // Setup networking varaibles
            InetAddress ip = InetAddress.getLocalHost();
            int port = cdht.getPort(this.sending_peer);
            DatagramSocket socket = new DatagramSocket();
            socket.setSoTimeout(SOCKET_TIMEOUT);

            // Create buffer to send file data (dynamically allocated based on MSS size)
            byte[] send_buffer;
            // Create buffer to receive acks.
            byte[] rcv_buffer = new byte[10];
            // Create buffer for storing header data.
            byte[] header_data; 
            // Stores how much of the file has been read so far.
            long file_len = this.file.length();
            // Used for Sequence numbers.
            int curr_len = 0;
            // Flag for indiciating whether we have reached the end of the file.
            int eof_flag = 0;

            // Get a ByteStream from the File.
            ByteArrayInputStream header_data_stream;
            BufferedInputStream file_data_stream = new BufferedInputStream(new FileInputStream(this.file));
            
            long size = MSS;
            while (file_len > 0) {
                
                // Send MSS bytes of data if the filesize is large enough, otherwise send the remainder of the file.
                if (file_len < MSS) {
                    size = file_len;
                    eof_flag = 1;
                } else {
                    size = MSS;
                }

                // Reduce file size by packet size.
                file_len -= size;
                // Increase the amount of file data read to be sent in the packet header.
                curr_len += size;

                // Create a byte array input stream for the packet header.
                header_data = createPacketHeader(curr_len, eof_flag);
                header_data_stream = new ByteArrayInputStream(header_data);
                
                // Read in the header to first TRANSFER_HEADER_LEN bytes then read in the rest from the file stream.
                send_buffer = new byte[cdht.TRANSFER_HEADER_LEN + (int) size];
                header_data_stream.read(send_buffer, 0, cdht.TRANSFER_HEADER_LEN);
                file_data_stream.read(send_buffer, cdht.TRANSFER_HEADER_LEN, (int) size);

                // Send the packet.
                DatagramPacket pkt = new DatagramPacket(send_buffer, send_buffer.length, ip, port);
                socket.send(pkt);

                // Wait for an acknowledgement packet from the receiver.
                DatagramPacket ack_packet = new DatagramPacket(rcv_buffer, rcv_buffer.length);
                socket.receive(ack_packet);
                String ack_response = new String(ack_packet.getData());
                String[] ack_data = ack_response.trim().split(" ");
                
                // If we did not receive an "ACK" Continue waiting for an ack.
                while (!ack_data[0].equals("ACK")) {
                    socket.receive(ack_packet);
                    ack_response = new String(ack_packet.getData());
                    ack_data = ack_response.trim().split(" ");
                }

                header_data_stream.close();
            }

            // Close all streams and the UDP socket.
            file_data_stream.close();
            socket.close();
        } catch (SocketException e) {
            e.printStackTrace();
            return;
        } catch (UnknownHostException e1) {
            e1.printStackTrace();
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
     * [UDP MSG TYPE=FS] [PEER_ID] [CURRENT FILE LEN (Sent Bytes)] [EOF = 0 => not the end of file.]
     * @param curr_len
     * @return byte array for the header.
     */
    private byte[] createPacketHeader(int curr_len, int end_of_file) {
        String header = "FS " + this.peer_id + " " + curr_len + " " + end_of_file;
        ByteArrayInputStream bais = new ByteArrayInputStream(header.getBytes());
        byte[] header_buf = new byte[cdht.TRANSFER_HEADER_LEN];
        bais.read(header_buf, 0, cdht.TRANSFER_HEADER_LEN);
        return header_buf;
    }
    
}