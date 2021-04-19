package tp1.impl.servers.rest;

import org.glassfish.jersey.client.ClientConfig;
import org.glassfish.jersey.client.ClientProperties;

import java.net.URI;
import java.util.*;

import jakarta.inject.Singleton;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import tp1.api.User;
import tp1.api.service.rest.RestSpreadsheets;
import tp1.api.service.rest.RestUsers;
import tp1.discovery.Discovery;

@Singleton
public class UsersResource implements RestUsers {
	public static final int PORT = 8080;
	public static final int MAX_RETRIES = 3;
	public static final long RETRY_PERIOD = 1000;
	public static final int CONNECTION_TIMEOUT = 10000;
	public static final int REPLY_TIMEOUT = 600;

	private final Map<String,User> users = new HashMap<String, User>();
	private static Discovery discovery;
	private static String domain;
	private static Client client;
	
	public UsersResource() {
	}

	public UsersResource(String domain, Discovery discovery) {
		UsersResource.domain = domain;
		UsersResource.discovery = discovery;
		
		ClientConfig config = new ClientConfig();
		config.property(ClientProperties.CONNECT_TIMEOUT, CONNECTION_TIMEOUT);
		config.property(ClientProperties.READ_TIMEOUT, REPLY_TIMEOUT);
		client = ClientBuilder.newClient(config);
	}
		
	@Override
	public String createUser(User user) {
		if(user.getUserId() == null || user.getPassword() == null || user.getFullName() == null || 
				user.getEmail() == null) {
			throw new WebApplicationException( Status.BAD_REQUEST ); //400
		}
		
		synchronized(users) {
			if(users.containsKey(user.getUserId())) {
				throw new WebApplicationException( Status.CONFLICT ); //409
			}
			
			users.put(user.getUserId(), user);
		}
		
		return user.getUserId();
	}


	@Override
	public User getUser(String userId, String password) {
		User user;
		synchronized(users) {
			user = users.get(userId);
		
			if( user == null ) {
				throw new WebApplicationException( Status.NOT_FOUND ); //404
			}
		
			if( !user.getPassword().equals( password)) {
				throw new WebApplicationException( Status.FORBIDDEN ); //403
			}
		}
		return user;
	}


	@Override
	public User updateUser(String userId, String password, User user) {
		// TODO Complete method
		
		User updateUser;
		synchronized(users) {
			updateUser = getUser(userId, password);

			if(user.getPassword() != null) {
				updateUser.setPassword(user.getPassword());
			}
		
			if(user.getFullName() != null) {
				updateUser.setFullName(user.getFullName());
			}

			if(user.getEmail() != null) {
				updateUser.setEmail(user.getEmail());
			}
		}
		
		return updateUser;
	}


	@Override
	public User deleteUser(String userId, String password) {
		// TODO Complete method
		
		User user;
		synchronized(users) {
			user = getUser(userId, password);
			users.remove(userId);
		}
		
		URI[] uri = null;
		while(uri == null) {
			try {
				uri = discovery.knownUrisOf(domain, "sheets");
				Thread.sleep(500);
			} catch (Exception e) {
			}
		}
		
		String serverUrl = uri[0].toString();
		WebTarget target = client.target( serverUrl ).path(RestSpreadsheets.PATH).path("/delete");

		short retries = 0;
		boolean success = false;
		
		while(!success && retries < MAX_RETRIES) {
	
			try {
				Response r = target.path(userId).request()
				.delete();
				
				if( r.getStatus() != Status.NO_CONTENT.getStatusCode() ) {
					throw new WebApplicationException(r.getStatus());
				}
				
				success = true;

			} catch (ProcessingException pe) {
				retries++;
				try { 
					Thread.sleep(RETRY_PERIOD);
				} catch (InterruptedException e) {
				}
			}
		}	
		return user;
	}


	@Override
	public List<User> searchUsers(String pattern) {
		// TODO Complete method
		
		List<User> userList = new ArrayList<User>();
		
		synchronized(users) {
			for(User u : users.values()) {
				if((u.getFullName().toLowerCase()).contains(pattern.toLowerCase())) {
					User user = new User(u.getUserId(), u.getFullName(), u.getEmail(), "");
					userList.add(user);
				}
			}
		}
		
		return userList;
	}

}