package clipper;

class ClipperException extends RuntimeException {
	public ClipperException(String message) {
		super(message);
	}
	
	public ClipperException(String message, Throwable cause) {
		super(message, cause);
	}
}
