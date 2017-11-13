import java.net.Socket;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.util.Random;

public class UdpClient {
    private static InputStream is;
    private static OutputStream os;
    private static String address;
    private static int portNumber;

    public static void main(String[] args) {
        try(Socket socket = new Socket("18.221.102.182", 38005)) {
            is = socket.getInputStream();
            os = socket.getOutputStream();
            address = socket.getInetAddress().getHostAddress();
            
            portNumber = handshake();

            Random random = new Random(); 
            double aveRTT = 0.00;
            int dataLength, UDPpacketsize;
            byte[] pseudoUDP, data, UDPpacket, IPv4packet;
            short pseudoUDPChecksum;
            for(int index = 1; index <= 12; index++) {
                dataLength = (int) Math.pow(2, index);
                
                pseudoUDP = createPseudoUDP(dataLength);
                //create packets with random data
                data = new byte[dataLength];            
                random.nextBytes(data);
                //fill pseudoUDP with random data
                for(int i = 0; i < dataLength; i++) {
                    pseudoUDP[i + 20] = data[i];
                }
                pseudoUDPChecksum = checkSum(pseudoUDP);

                //create UDP packets
                UDPpacketsize = 8 + dataLength;
                UDPpacket = new byte[UDPpacketsize];

                //DestPort
                UDPpacket[2] = (byte) (portNumber >> 8 & 0xFF);
                UDPpacket[3] = (byte) (portNumber & 0xFF);

                //length
                UDPpacket[4] = (byte) ((UDPpacketsize >> 8) & 0xFF);
                UDPpacket[5] = (byte) (UDPpacketsize & 0xFF);

                //checksum
                UDPpacket[6] = (byte) ((pseudoUDPChecksum >> 8) & 0xFF); 
                UDPpacket[7] = (byte) (pseudoUDPChecksum & 0xFF);

                for(int i = 0; i < dataLength; i++) {
                    UDPpacket[i + 8] = pseudoUDP[i + 20];
                }

                IPv4packet = createIPv4(UDPpacketsize, UDPpacket);
                aveRTT += calculateRTT(IPv4packet);
            }
            System.out.printf("Average RTT: %.2f", (aveRTT / 12));
            System.out.print("ms\n");
        } catch(IOException e) {
            e.printStackTrace();
        }
    }

    private static int handshake() throws IOException {
        byte[] deadbeef = {(byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF};
        byte[] handshake = createIPv4(4, deadbeef);
        os.write(handshake);
        System.out.print("Handshake response: ");
        printResponse();
        byte firstHalfPortNumber = (byte) is.read();
        byte secondHalfPortNumber = (byte) is.read();
        int portNumber = ((firstHalfPortNumber << 8 & 0xFF00) | secondHalfPortNumber & 0xFF);
        System.out.println("Port Number received: " + portNumber + "\n");
        return portNumber;
    }

    private static void printResponse() throws IOException {
        System.out.print("0x");
        for(int j = 0; j < 4; j++) {
            System.out.printf("%02X", is.read());
        }
        System.out.println();
    }

    private static byte[] createIPv4(int dataLength, byte[] data) {
        byte packet[] = new byte[20 + dataLength];
        int version = 4;
        int HLen = 5;

        packet[0] = (byte) ((version << 4 & 0xF0) | (HLen & 0xF));

        int length = (int) (20 + dataLength);
        packet[2] = (byte) ((length >> 8) & 0xFF);
        packet[3] = (byte) (byte) (length & 0xFF);

        //flag
        packet[6] = (byte) 64;
        //TTL
        packet[8] = (byte) 50;
        //UDP Protocol
        packet[9] = (byte) 17;

        int chkSum = 0;
        packet[10] = (byte) chkSum;
        packet[11] = (byte) chkSum;

        //SourceAddr
        for(int i = 0; i < 4; i++) {
            packet[12 + i] = 0;
        }

        destAddr(packet, 16);

        short checksum = checkSum(packet);
        packet[10] = (byte) (checksum >> 8 & 0xFF);
        packet[11] = (byte) (checksum & 0xFF);

        for(int i = 0; i < dataLength; i++) {
            packet[(20 + i)] = data[i];
        }
        return packet;
    }

    private static byte[] createPseudoUDP(int dataLength) {
        byte[] packet = new byte[20 + dataLength];

        destAddr(packet, 4);

        //UDP Protocol
        packet[9] = (byte) 17;

        //UDP length
        packet[10] = (byte) (((8 + dataLength) >> 8) & 0xFF);
        packet[11] = (byte) ((8 + dataLength) & 0xFF);

        //DestPort
        packet[14] = (byte) ((portNumber >> 8) & 0xFF);
        packet[15] = (byte) (portNumber & 0xFF);

        //length
        packet[16] = (byte) (((8 + dataLength) >> 8) & 0xFF);
        packet[17] = (byte) ((8 + dataLength) & 0xFF);

        System.out.println("Sending packet with " + dataLength + " bytes of data");
        return packet;
    }

    private static void destAddr(byte[] packet, int index) {
        String[] addr = address.split("\\.");
        int val;
        for(int i = 0; i < addr.length; i++) {
            val = Integer.valueOf(addr[i]);
            packet[index + i] = (byte) val;
        }
    }

    private static short checkSum(byte[] b) {
        int sum = 0, count = 0;
        byte firstHalf, secondHalf;

        while(count < b.length - 1) {
            firstHalf = b[count];
            secondHalf = b[count + 1];
            sum += ((firstHalf << 8 & 0xFF00) | (secondHalf & 0xFF));
            if((sum & 0xFFFF0000) > 0) {
                sum &= 0xFFFF;
                sum++;
            }
            count += 2;
        }

        if((b.length) % 2 == 1) {
            byte overflow = b[b.length - 1];
            sum += ((overflow << 8) & 0xFF00);
            if((sum & 0xFFFF0000) > 0) {
                sum &= 0xFFFF;
                sum++;
            }
        }
        return (short) ~(sum & 0xFFFF);
    }

    private static long calculateRTT(byte[] IPv4packet) throws IOException {
        long sendTime = System.currentTimeMillis();
        os.write(IPv4packet);
        System.out.print("Response: ");
        printResponse();
        long receiveTime = System.currentTimeMillis();
        long estimatedRTT =  receiveTime - sendTime;
        System.out.println("RTT: " + estimatedRTT + "ms\n");
        return estimatedRTT;
    }
}