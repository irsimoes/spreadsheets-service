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
	
	public void addOperation(Operation op) {
		System.out.println("versao antes de atualizar: " + version);
		synchronized(this) {
			version++;
			System.out.println("versao atualizada: " + version);
			try{
				operationLog.put(op.getVersion(), op);
			} catch(Exception e) {
				System.out.println(e.getMessage());
				e.printStackTrace();
				throw e;
			}
			System.out.println("added");
		}
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