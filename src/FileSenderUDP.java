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
    private Thread t;
    private volatile boolean shutdown;

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
            DatagramSocket socket = new DatagramSocket(cdht.DEFAULT_PORT + peer_id);

            // Create buffer to send file data (dynamically allocated based on MSS size)
            byte[] send_buffer;
            // Create buffer to receive acks.
            byte[] rcv_buffer = new byte[10];

            // Stores how much of the file has been read so far.
            long filelen = this.file.length();
            // Used for Sequence numbers.
            int curr_len = 0;

            // Get a ByteStream from the File.
            BufferedInputStream file_data_stream = new BufferedInputStream(new FileInputStream(this.file));

            // Create a byte array input stream for the packet header.
            byte[] header_data = createPacketHeader(curr_len);
            ByteArrayInputStream header_data_stream = new ByteArrayInputStream(header_data);

            long size = MSS;
            while (filelen > 0) {

                // Send MSS bytes of data if the filesize is large enough, otherwise send the remainder of the file.
                if (filelen < MSS) {
                    size = filelen;
                } else {
                    size = MSS;
                }

                // Reduce file size by packet size.
                filelen -= size;

                // Read in the header to first TRANSFER_HEADER_LEN bytes then read in the rest from the file stream.
                send_buffer = new byte[cdht.TRANSFER_HEADER_LEN + (int) size];
                header_data_stream.read(send_buffer, 0, cdht.TRANSFER_HEADER_LEN);
                file_data_stream.read(send_buffer, cdht.TRANSFER_HEADER_LEN, (int) size);

                // Send the packet.
                DatagramPacket pkt = new DatagramPacket(send_buffer, send_buffer.length, ip, port);
                socket.send(pkt);

                // Wait for an acknowledgement packet from the receiver.
                DatagramPacket request = new DatagramPacket(rcv_buffer, rcv_buffer.length);
                socket.receive(request);
            }

            // Close all streams and the UDP socket.
            header_data_stream.close();
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

    private byte[] createPacketHeader(int curr_len) {
        String header = "FS " + curr_len;
        ByteArrayInputStream bais = new ByteArrayInputStream(header.getBytes());
        byte[] header_buf = new byte[cdht.TRANSFER_HEADER_LEN];
        bais.read(header_buf, 0, cdht.TRANSFER_HEADER_LEN);
        return header_buf;
    }
    
}