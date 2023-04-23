package net.futureclient.nyan4;

import it.unimi.dsi.fastutil.longs.LongSet;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class NyanServer {
    private static final Logger LOGGER = LogManager.getLogger("NyanServer");
    private final ServerSocket server;
    private final Thread listenThread;
    private final Executor socketIOExecutor;

    public NyanServer() throws Exception {
        this.server = new ServerSocket(3459, 1, InetAddress.getByName("192.168.69.2"));
        this.socketIOExecutor = Executors.newSingleThreadExecutor();
        this.listenThread = new Thread(this::listen);
        this.listenThread.start();
        LOGGER.info("nyanserver listening");
    }

    private void listen() {
        try {
            while (true) {
                Socket s = server.accept();
                socketIOExecutor.execute(() -> io(s));
            }
        } catch (IOException ex) {
            // when shutdown is called, .accept will fail
            LOGGER.warn("NyanServer shutdown", ex);
        }
    }

    private void io(Socket s) {
        try {
            LOGGER.info("got connection to nyanserver");
            s.setSoTimeout(10000);
            DataInputStream in = new DataInputStream(s.getInputStream());
            DataOutputStream out = new DataOutputStream(s.getOutputStream());
            String secretToken = in.readUTF();
            if (!secretToken.equals("def7fa9dd0c96bc1dcd2913d19120b7c89c026407fd67053774d316bd90b3459815479eef495f7f93f8f845c544515b77360b60e1214ed786820cb4d94e6abcd v1")) {
                LOGGER.info("wrong token");
                return;
            }
            LongSet seeds = NyanDatabase.getSomeRngsToBeProcessed();
            LOGGER.info("nyanserver sending " + seeds.size() + " seeds");
            out.writeInt(seeds.size());
            for (long seed : seeds) {
                out.writeLong(seed);
            }
            // some time passes...
            int numProcessed = in.readInt();
            LOGGER.info("nyanserver got back " + numProcessed + " seeds");
            List<NyanDatabase.ProcessedSeed> processed = new ArrayList<>(numProcessed);
            for (int i = 0; i < numProcessed; i++) {
                long seed = in.readLong();
                if (!seeds.contains(seed)) {
                    LOGGER.warn("naughty");
                    return;
                }
                int steps = in.readInt();
                short x = in.readShort();
                short z = in.readShort();
                processed.add(new NyanDatabase.ProcessedSeed(seed, steps, x, z));
            }
            LOGGER.info("received all processed seeds");
            NyanDatabase.saveProcessedRngSeeds(processed);
            LOGGER.info("saved all processed seeds to db");
        } catch (IOException ex) {
            LOGGER.warn("IO failed", ex);
        } finally {
            try {
                s.close();
            } catch (Throwable th) {}
        }
    }


    public void shutdown() {
        try {
            server.close();
        } catch (IOException ex) {
            LOGGER.warn("Failed to stop executor", ex);
        }
    }
}
