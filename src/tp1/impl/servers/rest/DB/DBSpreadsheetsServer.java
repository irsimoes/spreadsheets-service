package tp1.impl.servers.rest.DB;

import java.net.InetAddress;
import java.net.URI;
import java.util.logging.Logger;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;

import org.glassfish.jersey.jdkhttp.JdkHttpServerFactory;
import org.glassfish.jersey.server.ResourceConfig;

import tp1.discovery.Discovery;
import tp1.util.InsecureHostnameVerifier;

public class DBSpreadsheetsServer {

	private static Logger Log = Logger.getLogger(DBSpreadsheetsServer.class.getName());

	static {
		System.setProperty("java.net.preferIPv4Stack", "true");
		System.setProperty("java.util.logging.SimpleFormatter.format", "%4$s: %5$s\n");
	}
		
	public static final int PORT = 8080;
	public static final String SERVICE = "sheets";
	
	//arg0 - domain, arg1 - boolean purgeDB, arg2 - serverSecret, arg3 - DBAPIkey, arg4 - DBAPISecret, arg5 - DBAccessToken, arg6 - GoogleAPIKey
	public static void main(String[] args) {
		try {
		String ip = InetAddress.getLocalHost().getHostAddress();

		HttpsURLConnection.setDefaultHostnameVerifier(new InsecureHostnameVerifier());
		
		String serverURI = String.format("https://%s:%s/rest", ip, PORT);
		
		Discovery discovery = Discovery.getInstance();
		discovery.start(args[0], SERVICE, serverURI);
		
		ResourceConfig config = new ResourceConfig();
		config.register(new DBSpreadsheetsResource(args[0], Boolean.parseBoolean(args[1]), args[2], args[3], args[4], args[5], args[6], discovery));
		JdkHttpServerFactory.createHttpServer( URI.create(serverURI), config, SSLContext.getDefault());

		Log.info(String.format("%s Server ready @ %s\n",  SERVICE, serverURI));
			
		} catch( Exception e) {
			Log.severe(e.getMessage());
		}
	}

}
