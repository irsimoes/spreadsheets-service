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
//	private String domain;
//	private String service;
//	private String serviceURI;
	private Map<String, Map<String, Long>> services = new HashMap<String, Map<String, Long>>(); //service name, uri, timestamp
//	private boolean announce;
	private boolean active;

	private static Discovery instance;

	/**
	 * @param  serviceName the name of the service to announce
	 * @param  serviceURI an uri string - representing the contact endpoint of the service being announced
	 */
/*	public Discovery(String serviceName, String serviceURI) { //server
		this.addr = DISCOVERY_ADDR;
		this.services = new HashMap<String, Map<String, Long>>();
		this.serviceName = serviceName;
		this.serviceURI  = serviceURI;
		this.announce = true;
		this.active = true;
	} */
	
	private Discovery() { //client 
/*		this.addr = DISCOVERY_ADDR;
		this.services = new HashMap<String, Map<String, Long>>();
		this.announce = false;
		this.active = true;		*/
	}

	static synchronized public Discovery getInstance() {
		if( instance == null)
			instance = new Discovery();
		return instance;
	}
	
	/**
	 * Starts sending service announcements at regular intervals... 
	 */
	public void start(String domain, String service, String serviceURI) {
		//Log.info(String.format("Starting Discovery announcements on: %s for: %s -> %s", addr, serviceName, serviceURI));

		this.addr = DISCOVERY_ADDR;
		String serviceName = String.format("%s:%s", domain, service);
		this.active = true;
		
		try {
			MulticastSocket ms = new MulticastSocket( addr.getPort());
			ms.joinGroup(addr, NetworkInterface.getByInetAddress(InetAddress.getLocalHost()));
			
//			if(announce) {
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
//			}
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
							String serviceKey = msgElems[0];
							String uri = msgElems[1];
							if(services.get(serviceKey) == null) {
								Map<String, Long> uriTimestamps = new HashMap<String, Long>();
								services.put(serviceKey, uriTimestamps);
							}
							services.get(serviceKey).put(uri, System.currentTimeMillis());
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