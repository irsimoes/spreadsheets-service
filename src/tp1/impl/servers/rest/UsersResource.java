package tp1.impl.servers.rest;

import java.util.logging.Logger;
import java.util.*;

import jakarta.inject.Singleton;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response.Status;
import tp1.api.User;
import tp1.api.service.rest.RestUsers;

@Singleton
public class UsersResource implements RestUsers {

	private final Map<String,User> users = new HashMap<String, User>();

	private static Logger Log = Logger.getLogger(UsersResource.class.getName());
	
	public UsersResource() {
	}
		
	@Override
	public String createUser(User user) {
		Log.info("createUser : " + user);
		
		// Check if user is valid, if not return BAD REQUEST (400)
		if(user.getUserId() == null || user.getPassword() == null || user.getFullName() == null || 
				user.getEmail() == null) {
			Log.info("User object invalid.");
			throw new WebApplicationException( Status.BAD_REQUEST );
		}
		
		// Check if userId does not exist exists, if not return HTTP CONFLICT (409)
		if( users.containsKey(user.getUserId())) {
			Log.info("User already exists.");
			throw new WebApplicationException( Status.CONFLICT );
		}

		//Add the user to the map of users
		users.put(user.getUserId(), user);
		
		return user.getUserId();
	}


	@Override
	public User getUser(String userId, String password) {
		Log.info("getUser : user = " + userId + "; pwd = " + password);
		
		User user = users.get(userId);
		
		// Check if user exists 
		if( user == null ) {
			Log.info("User does not exist.");
			throw new WebApplicationException( Status.NOT_FOUND );
		}
		
		//Check if the password is correct
		if( !user.getPassword().equals( password)) {
			Log.info("Password is incorrect.");
			throw new WebApplicationException( Status.FORBIDDEN );
		}
		
		return user;
	}


	@Override
	public User updateUser(String userId, String password, User user) {
		Log.info("updateUser : user = " + userId + "; pwd = " + password + " ; user = " + user);
		// TODO Complete method
		
		User updateUser = getUser(userId, password);

		if(user.getPassword() != null) {
			updateUser.setPassword(user.getPassword());
		}
		
		if(user.getFullName() != null) {
			updateUser.setFullName(user.getFullName());
		}

		if(user.getEmail() != null) {
			updateUser.setEmail(user.getEmail());
		}
		
		return updateUser;
	}


	@Override
	public User deleteUser(String userId, String password) {
		Log.info("deleteUser : user = " + userId + "; pwd = " + password);
		// TODO Complete method
		
		User user = this.getUser(userId, password);
		users.remove(userId);
		
		return user;
	}


	@Override
	public List<User> searchUsers(String pattern) {
		Log.info("searchUsers : pattern = " + pattern);
		// TODO Complete method
		
		List<User> userList = new ArrayList<User>();
		for(User u : users.values()) {
			if((u.getFullName().toLowerCase()).contains(pattern.toLowerCase())) {
				User user = new User(u.getUserId(), u.getFullName(), u.getEmail(), "");
				userList.add(user);
			}
		}
		
		return userList;
	}

}
