package tp1.impl.servers.rest.replication;

import java.util.Map;
import java.util.TreeMap;

public class ReplicationManager {
	private Map<Integer, Operation> operationLog = new TreeMap<Integer, Operation>();
	private int version;
	
	public ReplicationManager() {
		version = 0;
	}
	
	public int getCurrentVersion() {
		return version;
	}
	
	public synchronized void addOperation(Operation op) {
			version++;
			operationLog.put(op.getVersion(), op);
	}

	public Operation[] getOperations(int first, int nOps) {
		Operation[] operations = new Operation[nOps];
		for(int i = 0; i < nOps; i++) {
			operations[i] = operationLog.get(first+i);
		}
		return operations;
	}

	public Operation[] getAll() {
		Operation[] operations = new Operation[operationLog.size()];
		operationLog.values().toArray(operations);
		return operations;
	}
}