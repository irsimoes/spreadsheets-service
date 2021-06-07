package tp1.impl.servers.rest.replication;

public class Operation {

	private int version;
	private String operation;
	private String[] params;
	
	public Operation() {
	}
	
	public Operation(int version, String operation, String[] params) {
		this.version = version;
		this.operation = operation;
		this.params = params;
	}

	public int getVersion() {
		return version;
	}
	
	public void setVersion(int version) {
		this.version = version;
	}
	
	public String getOperation() {
		return operation;
	}
	
	public void setOperation(String operation) {
		this.operation = operation;
	}
	
	public String[] getParams() {
		return params;
	}
	
	public void setParams(String[] params) {
		this.params = params;
	}
}
