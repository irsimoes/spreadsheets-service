package tp1.impl.servers.rest.DB.arguments;

public class CreateFileArgs {
	final String path, mode;
	final boolean autorename, mute, strict_conflict;

	public CreateFileArgs(String path, String mode, boolean autorename, boolean mute, boolean strict_conflict) {
		this.path = path;
		this.mode = mode;
		this.autorename = autorename;
		this.mute = mute;
		this.strict_conflict = strict_conflict;
	}
}