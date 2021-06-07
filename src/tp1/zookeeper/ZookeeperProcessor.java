package tp1.zookeeper;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;

public class ZookeeperProcessor implements Watcher {
	private ZooKeeper zk;

	/**
	 * @param  serviceName the name of the service to announce
	 */
	public ZookeeperProcessor( String hostPort) throws Exception {
		zk = new ZooKeeper(hostPort, 3000, this);

	}
	
	public String write( String path, CreateMode mode) {
		try {
			return zk.create(path, null, ZooDefs.Ids.OPEN_ACL_UNSAFE, mode);
		} catch (KeeperException | InterruptedException e) {
			e.printStackTrace();
			return null;
		}
	}
	
	public String write( String path, String value, CreateMode mode) {
		try {
			return zk.create(path, value.getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, mode);
		} catch (KeeperException | InterruptedException e) {
			e.printStackTrace();
			return null;
		}
	}

	public List<String> getChildren( String path, Watcher watch) {
		try {
			return zk.getChildren(path, watch);
		} catch (KeeperException | InterruptedException e) {
			e.printStackTrace();
			return null;
		}
	}

	public List<String> getChildren( String path) {
		try {
			return zk.getChildren(path, false);
		} catch (KeeperException | InterruptedException e) {
			e.printStackTrace();
			return null;
		}
	}

	@Override
	public void process(WatchedEvent event) {
		System.out.println( event);
	}
	
	public String getValue(String path) {
		try {
			//meter tostring
			return new String(zk.getData(path, false, null), StandardCharsets.UTF_8);
		} catch (KeeperException | InterruptedException e) {
			e.printStackTrace();
			return null;
		}
	}
	
}
