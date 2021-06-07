package tp1.impl.servers.rest.replication;

import java.net.InetAddress;
import java.net.URI;
import java.util.logging.Logger;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;

import org.apache.zookeeper.CreateMode;
import org.glassfish.jersey.jdkhttp.JdkHttpServerFactory;
import org.glassfish.jersey.server.ResourceConfig;

import tp1.discovery.Discovery;
import tp1.util.InsecureHostnameVerifier;
import tp1.zookeeper.ZookeeperProcessor;

public class SpreadsheetsRepServer {

	private static Logger Log = Logger.getLogger(SpreadsheetsRepServer.class.getName());

	static {
		System.setProperty("java.net.preferIPv4Stack", "true");
		System.setProperty("java.util.logging.SimpleFormatter.format", "%4$s: %5$s\n");
	}

	public static final int PORT = 8080;
	public static final String SERVICE = "sheets";

	public static void main(String[] args) {
		try {
			String ip = InetAddress.getLocalHost().getHostAddress();

			HttpsURLConnection.setDefaultHostnameVerifier(new InsecureHostnameVerifier());

			String serverURI = String.format("https://%s:%s/rest", ip, PORT);

			Discovery discovery = Discovery.getInstance();
			discovery.start(args[0], SERVICE, serverURI);
			
			ZookeeperProcessor zk = new ZookeeperProcessor("kafka:2181");
			String path = String.format("/%s", args[0]);

			ReplicationManager repManager = new ReplicationManager();
			ResourceConfig config = new ResourceConfig();

			System.out.println(zk.write(path, CreateMode.PERSISTENT));
			System.out.println(zk.write(String.format("%s/sheets_", path), serverURI, CreateMode.EPHEMERAL_SEQUENTIAL));
			System.out.println(URI.create(serverURI).toString());

			config.register(new SpreadsheetsRepResource(args[0], serverURI, args[1], args[2], discovery, zk, repManager));
			config.register(new VersionFilter(repManager));
			JdkHttpServerFactory.createHttpServer(URI.create(serverURI), config, SSLContext.getDefault());
			
			Log.info(String.format("%s Server ready @ %s\n", SERVICE, serverURI));
			
		} catch (Exception e) {
			Log.severe(e.getMessage());
		}
	}

}
