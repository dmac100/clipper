package clipper;

public class IntPoint {
	public long x;
	public long y;

	public IntPoint() {
	}

	public IntPoint(long x, long y) {
		this.x = x;
		this.y = y;
	}

	public IntPoint(IntPoint pt) {
		this.x = pt.x;
		this.y = pt.y;
	}
	
	public String toString() {
		return "(" + x + ", " + y + ")";
	}
}