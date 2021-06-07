package tp1.impl.servers.rest.replication;

public class MostRecentVersion {
	int version;
	String url;

	MostRecentVersion() {
		this.version = 0;
		this.url = "";
	}

	MostRecentVersion(int version, String url) {
		this.version = version;
		this.url = url;
	}

	public int getVersion() {
		return version;
	}

	public String getUrl() {
		return url;
	}

	public void setVersion(int version) {
		this.version = version;
	}

	public void setUrl(String url) {
		this.url = url;
	}

	public synchronized void setHigherVersion(int version, String url) {
		if(this.version < version) {
			setVersion(version);
			setUrl(url);
		}
	}
}