package tp1.discovery;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.URI;
import java.util.*;
import java.util.Map.Entry;

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
	private Map<String, Map<URI, Long>> services = new HashMap<String, Map<URI, Long>>(); //service name, uri, timestamp
	private boolean active;

	private static Discovery instance;
	
	private Discovery() {
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

		this.addr = DISCOVERY_ADDR;
		String serviceName = String.format("%s:%s", domain, service);
		this.active = true;
		
		try {
			MulticastSocket ms = new MulticastSocket( addr.getPort());
			ms.joinGroup(addr, NetworkInterface.getByInetAddress(InetAddress.getLocalHost()));
			
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
							String serviceKey = msgElems[0];
							URI uri = URI.create(msgElems[1]);
							if(services.get(serviceKey) == null) {
								Map<URI, Long> uriTimestamps = new HashMap<URI, Long>();
								services.put(serviceKey, uriTimestamps);
							}
							services.get(serviceKey).put(uri, System.currentTimeMillis());
						}
					} catch (IOException e) {
						// do nothing
					}
				}
			}).start();

			new Thread(() -> {
				while(active) {
					try {
						for (Map<URI, Long> map : services.values()) {
							Iterator<Entry<URI, Long>> it = map.entrySet().iterator();
							while(it.hasNext()){
								Entry<URI, Long> entry = it.next();
								if(System.currentTimeMillis() - entry.getValue() > DISCOVERY_TIMEOUT) it.remove();
							}
						}
						Thread.sleep(DISCOVERY_PERIOD);
					} catch (Exception e) {
						e.printStackTrace();
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
		String serviceName = String.format("%s:%s", domain, service);
		URI[] uris;
		Map<URI, Long> aux = services.get(serviceName);
		if(aux == null) {
			uris = null;
		} else {
			Set<URI> knownUris = aux.keySet();
			uris = knownUris.toArray(new URI[knownUris.size()]);
		}
		return uris;
	}	
	
}