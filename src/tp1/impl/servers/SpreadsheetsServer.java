package tp1.impl.servers;

import java.net.InetAddress;
import java.net.URI;
import java.util.logging.Logger;

import org.glassfish.jersey.jdkhttp.JdkHttpServerFactory;
import org.glassfish.jersey.server.ResourceConfig;

import tp1.discovery.Discovery;
import tp1.impl.servers.rest.SpreadsheetsResource;

public class SpreadsheetsServer {

	private static Logger Log = Logger.getLogger(SpreadsheetsServer.class.getName());

	static {
		System.setProperty("java.net.preferIPv4Stack", "true");
		System.setProperty("java.util.logging.SimpleFormatter.format", "%4$s: %5$s\n");
	}
		
	public static final int PORT = 8080;
	public static final String SERVICE = "sheets";
		
	public static void main(String[] args) {
		try {
		String ip = InetAddress.getLocalHost().getHostAddress();

		String serverURI = String.format("http://%s:%s/rest", ip, PORT);
		
		Discovery discovery = Discovery.getInstance();
		discovery.start(args[0], SERVICE, serverURI);
		
		ResourceConfig config = new ResourceConfig();
		config.register(new SpreadsheetsResource(args[0], discovery));
		JdkHttpServerFactory.createHttpServer( URI.create(serverURI), config);

		Log.info(String.format("%s Server ready @ %s\n",  SERVICE, serverURI));
			
		//More code can be executed here...
		} catch( Exception e) {
			Log.severe(e.getMessage());
		}
	}

}
