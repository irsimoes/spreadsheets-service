package tp1.impl.servers.rest;

import java.util.Set;

public class ValuesResult {
	private String[][] values;
	private Set<String> sharedWith;
	private long twServer;
	
	public ValuesResult() {
	}
	
	public ValuesResult(String[][] values, Set<String> sharedWith, long twServer) {
		this.values = values;
		this.sharedWith = sharedWith;
	}
	
	public String[][] getValues() {
		return values;
	}
	
	public void setValues(String[][] values) {
		this.values = values;
	}
	
	public Set<String> getSharedWith() {
		return sharedWith;
	}
	
	public void setSharedWith(Set<String> sharedWith) {
		this.sharedWith = sharedWith;
	}
	
	public long getTwServer() {
		return twServer;
	}
	
	public void setTwServer(long twServer) {
		this.twServer = twServer;
	}

}
