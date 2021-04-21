package tp1.impl.servers.soap;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.xml.namespace.QName;
import com.sun.xml.ws.client.BindingProviderProperties;

import jakarta.xml.ws.BindingProvider;
import jakarta.inject.Singleton;
import jakarta.jws.WebService;
import jakarta.xml.ws.Service;
import jakarta.xml.ws.WebServiceException;
import tp1.api.User;
import tp1.api.service.soap.SheetsException;
import tp1.api.service.soap.SoapSpreadsheets;
import tp1.api.service.soap.SoapUsers;
import tp1.api.service.soap.UsersException;
import tp1.discovery.Discovery;

@WebService(serviceName = SoapUsers.NAME,
targetNamespace = SoapUsers.NAMESPACE,
endpointInterface = SoapUsers.INTERFACE)

@Singleton
public class UsersWS implements SoapUsers {
	
	public final static String USERS_WSDL = "/users/?wsdl";
	
	public static final int PORT = 8080;
	public static final int MAX_RETRIES = 3;
	public static final long RETRY_PERIOD = 1000;
	public static final int CONNECTION_TIMEOUT = 10000;
	public static final int REPLY_TIMEOUT = 600;
	
	private final Map<String,User> users = new HashMap<String,User>();
	private static Discovery discovery;
	private static String domain;
	
	public UsersWS() {
	}

	public UsersWS(Discovery discovery, String domain) {
		UsersWS.discovery = discovery;
		UsersWS.domain = domain;
	}
	
	@Override
	public String createUser(User user) throws UsersException {
		// TODO Auto-generated method stub
		if(user.getUserId() == null || user.getPassword() == null || user.getFullName() == null || 
				user.getEmail() == null) {
			throw new UsersException();
		}
		
		synchronized(users) {
			if(users.containsKey(user.getUserId())) {
				throw new UsersException();
			}
			
			users.put(user.getUserId(), user);
		}
		
		return user.getUserId();
	}

	@Override
	public User getUser(String userId, String password) throws UsersException {
		// TODO Complete method
		User user;
		synchronized(users) {
			user = users.get(userId);
		
			if( user == null ) {
				throw new UsersException();
			}
		
			if( !user.getPassword().equals( password)) {
				throw new UsersException();
			}
		}
		return user;
	}

	@Override
	public User updateUser(String userId, String password, User user) throws UsersException {
		// TODO Auto-generated method stub
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
	public User deleteUser(String userId, String password) throws UsersException {
		// TODO Auto-generated method stub
		
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
		SoapSpreadsheets sheets = null;
		
		short retries = 0;
		boolean success = false;
		
		while(!success && retries < MAX_RETRIES) {
			try {
				QName QNAME = new QName(SoapSpreadsheets.NAMESPACE, SoapSpreadsheets.NAME);
				Service service = Service.create( new URL(serverUrl + SpreadsheetsWS.SHEETS_WSDL), QNAME);
				sheets = service.getPort( tp1.api.service.soap.SoapSpreadsheets.class);
				success = true;
			} catch (WebServiceException e) {
				retries++;
			} catch (MalformedURLException e) {
			}
		}
		
		((BindingProvider) sheets).getRequestContext().put(BindingProviderProperties.CONNECT_TIMEOUT, CONNECTION_TIMEOUT);
		((BindingProvider) sheets).getRequestContext().put(BindingProviderProperties.REQUEST_TIMEOUT, REPLY_TIMEOUT);
		
		retries = 0; success = false;
		
		while(!success && retries < MAX_RETRIES) {
			
			try{
				sheets.deleteUserSpreadsheets(userId);
				success = true;
			} catch(WebServiceException wse) {
				retries++;
				try {
					Thread.sleep(RETRY_PERIOD);
				} catch (InterruptedException e) {
				}
			} catch(SheetsException se) {
				throw new UsersException();
			}
		}
		
		return user;
	}

	@Override
	public List<User> searchUsers(String pattern) throws UsersException {
		// TODO Auto-generated method stub
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

	@Override
	public boolean userExists(String userId) throws UsersException {
		// TODO Auto-generated method stub
		
		synchronized(users) {
			User user = users.get(userId);
		
			if( user == null ) {
				throw new UsersException();
			}
		}
		return true;
	}

}