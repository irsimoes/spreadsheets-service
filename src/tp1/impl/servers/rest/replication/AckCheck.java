package tp1.impl.servers.rest.replication;

public class AckCheck {
	int acks;

	public AckCheck() {
		this.acks = 0;
	}
	
	public void acksInc() {
		acks++;
	}
	
	public boolean hasSucceded() {
		return acks >= 1;
	}
}
