package tp1.discovery;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.URI;
import java.util.*;
//import java.util.logging.Logger;

/**
 * <p>A class to perform service discovery, based on periodic service contact endpoint 
 * announcements over multicast communication.</p>
 * 
 * <p>Servers announce their *name* and contact *uri* at regular intervals. The server actively
 * collects received announcements.</p>
 * 
 * <p>Service announcements have the following format:</p>
 * 
 * <p>&lt;service-name-string&gt;&lt;delimiter-char&gt;&lt;service-uri-string&gt;</p>
 */

public class Discovery {
	//private static Logger Log = Logger.getLogger(Discovery.class.getName());

	static {
		// addresses some multicast issues on some TCP/IP stacks
		System.setProperty("java.net.preferIPv4Stack", "true");
		// summarizes the logging format
		System.setProperty("java.util.logging.SimpleFormatter.format", "%4$s: %5$s");
	}
	
	// The pre-aggreed multicast endpoint assigned to perform discovery. 
	public static final InetSocketAddress DISCOVERY_ADDR = new InetSocketAddress("226.226.226.226", 2266);
	static final int DISCOVERY_PERIOD = 1000;
	static final int DISCOVERY_TIMEOUT = 5000;

	// Used separate the two fields that make up a service announcement.
	private static final String DELIMITER = "\t";

	private InetSocketAddress addr;
	private String serviceName;
	private String serviceURI;
	private Map<String, Map<String, Long>> services; //service name, uri, timestamp // mapa<dominio,mapa<servname,coord<>
	private boolean announce;
	private boolean active;

	/**
	 * @param  serviceName the name of the service to announce
	 * @param  serviceURI an uri string - representing the contact endpoint of the service being announced
	 */
	public Discovery(String serviceName, String serviceURI) { //server
		this.addr = DISCOVERY_ADDR;
		this.services = new HashMap<String, Map<String, Long>>();
		this.serviceName = serviceName;
		this.serviceURI  = serviceURI;
		this.announce = true;
		this.active = true;
	}
	
	public Discovery() { //client 
		this.addr = DISCOVERY_ADDR;
		this.services = new HashMap<String, Map<String, Long>>();
		this.announce = false;
		this.active = true;
	}
	
	/**
	 * Starts sending service announcements at regular intervals... 
	 */
	public void start() {
		//Log.info(String.format("Starting Discovery announcements on: %s for: %s -> %s", addr, serviceName, serviceURI));
		
		try {
			MulticastSocket ms = new MulticastSocket( addr.getPort());
			ms.joinGroup(addr, NetworkInterface.getByInetAddress(InetAddress.getLocalHost()));
			
			if(announce) {
			byte[] announceBytes = String.format("%s%s%s", serviceName, DELIMITER, serviceURI).getBytes();
			DatagramPacket announcePkt = new DatagramPacket(announceBytes, announceBytes.length, addr);
			
			// start thread to send periodic announcements
			new Thread(() -> {
				while(active) {
					try {
						ms.send(announcePkt);
						Thread.sleep(DISCOVERY_PERIOD);
					} catch (Exception e) {
						e.printStackTrace();
						// do nothing
					}
				}
			}).start();
			}
			// start thread to collect announcements
			new Thread(() -> {
				DatagramPacket pkt = new DatagramPacket(new byte[1024], 1024);
				while(active) {
					try {
						pkt.setLength(1024);
						ms.receive(pkt);
						String msg = new String( pkt.getData(), 0, pkt.getLength());
						String[] msgElems = msg.split(DELIMITER);
						if( msgElems.length == 2) {	//periodic announcement
							//System.out.printf( "FROM %s (%s) : %s\n", pkt.getAddress().getCanonicalHostName(), 
									//pkt.getAddress().getHostAddress(), msg);
							//TODO: to complete by recording the received information from the other node.
							String service = msgElems[0];
							String uri = msgElems[1];
							if(services.get(service) == null) {
								Map<String, Long> uriTimestamps = new HashMap<String, Long>();
								services.put(service, uriTimestamps);
							}
							services.get(service).put(uri, System.currentTimeMillis());
						}
					} catch (IOException e) {
						// do nothing
					}
				}
			}).start();
		} catch (Exception e) {
			e.printStackTrace();
		}

	}

	/**
	 * Returns the known servers for a service.
	 * 
	 * @param  serviceName the name of the service being discovered
	 * @return an array of URI with the service instances discovered. 
	 * 
	 */
	
	public void stop() {
		active = false;
	}
	
	public URI[] knownUrisOf(String domain, String service) {
		//TODO: You have to implement this!!
		String serviceName = String.format("%s:%s", domain, service);
		URI[] uris;
		Map<String, Long> aux = services.get(serviceName);
		if(aux == null) {
			uris = null;
		} else {
			Set<String> knownUris = aux.keySet();
			uris = new URI[knownUris.size()];
			int i = 0;
			for(String uri : knownUris) {
				uris[i++] = URI.create(uri);
			}
		}
		return uris;
	}	
	
}