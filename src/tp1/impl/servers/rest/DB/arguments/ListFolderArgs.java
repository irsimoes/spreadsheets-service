package tp1.impl.servers.rest.DB.arguments;

public class ListFolderArgs {
	final String path;
	final boolean recursive, include_media_info, include_deleted, include_has_explicit_shared_members, include_mounted_folders;
	
	public ListFolderArgs(String path, boolean recursive) {
		this.path = path;
		this.recursive = recursive;
		this.include_media_info = false;
		this.include_deleted = false;
		this.include_mounted_folders = false;
		this.include_has_explicit_shared_members = false;
	}	
}
