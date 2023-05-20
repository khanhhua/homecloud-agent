package com.fatmandesigner.homecloudagent;

import org.apache.ftpserver.FtpServer;
import org.apache.ftpserver.FtpServerFactory;
import org.apache.ftpserver.ftplet.FtpException;
import org.apache.ftpserver.ftplet.UserManager;
import org.apache.ftpserver.usermanager.PropertiesUserManagerFactory;
import org.apache.ftpserver.usermanager.SaltedPasswordEncryptor;
import org.apache.log4j.Logger;
import org.apache.log4j.Priority;

import java.io.*;
import java.net.*;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.Properties;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public final class HomeCloudAgent {

    private static Logger logger = Logger.getLogger(HomeCloudAgent.class);
    private String baseServiceUrl = null;
    private String secretKey = null;
    private FtpServer server = null;

    private long ipRefreshRate = 5L;

    private String ipv6 = null; // "192.168.178.2"

    public HomeCloudAgent(Properties props) {
        this.baseServiceUrl = props.getProperty("homecloudagent.service.url");
        this.ipRefreshRate = Long.parseLong((String) props.getOrDefault("homecloudagent.service.ipRefreshRate", "5"));
        this.secretKey = props.getProperty("homecloudagent.service.secretKey");
    }

    public void runFtpServer() throws FtpException {
        PropertiesUserManagerFactory userManagerFactory = new PropertiesUserManagerFactory();
        userManagerFactory.setFile(new File("users.properties"));
        userManagerFactory.setPasswordEncryptor(new SaltedPasswordEncryptor());
        UserManager um = userManagerFactory.createUserManager();

        FtpServerFactory serverFactory = new FtpServerFactory();
        serverFactory.setUserManager(um);

        this.server = serverFactory.createServer();
        this.server.start();
    }
    public void advertiseIpV6() throws IOException {
        if (this.ipv6 == null) {
            discoverIPv6();
        }

        String serviceUrl = this.baseServiceUrl + "/api/advertise";
        logger.log(Priority.INFO, "Advertising to " + serviceUrl);
        URL url = new URL(serviceUrl);
        HttpURLConnection conn = (HttpURLConnection)url.openConnection();
        conn.setDoOutput(true);
        conn.setConnectTimeout(30000);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("x-secret-key", this.secretKey);
        conn.connect();

        String jsonBody = String.format("{\"device\":{\"hostname\":\"homecloud\",\"ipv6\":\"%s\"}}", this.ipv6);
        BufferedWriter out =
                new BufferedWriter(new OutputStreamWriter(conn.getOutputStream()));
        out.write(jsonBody);
        out.close();

        if (200 != conn.getResponseCode()) {
            logger.error("IPV6 could not be updated.");
        }
        conn.disconnect();
    }

    public void discoverIPv6() throws SocketException {
        logger.info("Inspecting network interfaces for an IPv6 address...");

        Enumeration<NetworkInterface> nets = NetworkInterface.getNetworkInterfaces();
        for (Iterator<NetworkInterface> it = nets.asIterator(); it.hasNext(); ) {
            NetworkInterface networkInterface = it.next();

            if (networkInterface.isLoopback()) {
                continue;
            }

            for (InetAddress inetAddress : Collections.list(networkInterface.getInetAddresses())) {
                if (inetAddress instanceof Inet6Address) {
                    Inet6Address a = (Inet6Address)inetAddress;
                    String ipv6 = a.getHostAddress();
                    if (ipv6.indexOf("%") != -1) {
                        ipv6 = ipv6.substring(0, ipv6.indexOf("%"));
                    }

                    this.ipv6 = ipv6;

                    break;
                }
            }
        }

        if (this.ipv6 == null) {
            logger.warn("Could not identify IPv6 on this host");
        } else {
            logger.debug(String.format("IPv6: %s", this.ipv6));
        }
    }

    public static void main(String[] args) throws IOException {
        InputStream appPropertiesIn = HomeCloudAgent.class.getClassLoader().getResourceAsStream("app.properties");
        Properties props = new Properties();
        props.load(appPropertiesIn);

        HomeCloudAgent agent = new HomeCloudAgent(props);

        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(2);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);

        // Who the hell is gonna monitor ME?!
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    agent.runFtpServer();
                } catch (FtpException e) {
                    throw new RuntimeException(e);
                }
            }
        });

        Runnable advertisingTask = new Runnable() {
            @Override
            public void run() {
                try {
                    agent.advertiseIpV6();
                } catch (IOException e) {
                    logger.error(e);
                    logger.warn("Advertise failed");
                }
            }
        };

        executor.scheduleAtFixedRate(advertisingTask, 0, agent.ipRefreshRate, TimeUnit.SECONDS);

        Runtime.getRuntime().addShutdownHook(new Thread() {
            public void run() {
                System.out.println("Shutting down FTP server...");
                agent.server.stop();
            }
        });
    }
}
