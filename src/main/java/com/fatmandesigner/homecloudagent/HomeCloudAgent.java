package com.fatmandesigner.homecloudagent;

import org.apache.commons.logging.Log;
import org.apache.ftpserver.FtpServer;
import org.apache.ftpserver.FtpServerFactory;
import org.apache.ftpserver.ftplet.FtpException;
import org.apache.ftpserver.ftplet.UserManager;
import org.apache.ftpserver.usermanager.PropertiesUserManagerFactory;
import org.apache.ftpserver.usermanager.SaltedPasswordEncryptor;
import org.apache.log4j.Logger;
import org.apache.log4j.Priority;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.util.Properties;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public final class HomeCloudAgent {

    private static Logger logger = Logger.getLogger(HomeCloudAgent.class);
    private String baseServiceUrl = null;
    private FtpServer server = null;

    private long ipRefreshRate = 5L;

    public HomeCloudAgent(Properties props) {
        this.baseServiceUrl = props.getProperty("homecloudagent.service.url");
        this.ipRefreshRate = Long.parseLong((String) props.getOrDefault("homecloudagent.service.ipRefreshRate", "5"));
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
        String serviceUrl = this.baseServiceUrl + "/api/advertise";
        logger.log(Priority.INFO, "Advertising to " + serviceUrl);
        URL url = new URL(serviceUrl);
        HttpURLConnection conn = (HttpURLConnection)url.openConnection();
        conn.setConnectTimeout(30000);
        conn.setRequestMethod("GET");
        conn.connect();

        if (200 != conn.getResponseCode()) {
            logger.error("IPV6 could not be updated.");
        }
        conn.disconnect();
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
                    logger.warn("Advertise failed");
                }
            }
        };

        executor.scheduleAtFixedRate(advertisingTask, 0, agent.ipRefreshRate, TimeUnit.SECONDS);

        Runtime.getRuntime().addShutdownHook(new Thread() {
            public void run() {
                System.out.println("Shuting down FTP server...");
                agent.server.stop();
            }
        });
    }
}
