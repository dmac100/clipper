package clipper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

public class Polygon implements Iterable<IntPoint> {
	private List<IntPoint> points = new ArrayList<IntPoint>();
	
	public Polygon() {
	}
	
	public Polygon(Polygon polygon) {
		this.points = new ArrayList<IntPoint>(points);
	}

	public void add(long x, long y) {
		points.add(new IntPoint(x, y));
	}
	
	public void add(IntPoint point) {
		points.add(point);
	}

	public int size() {
		return points.size();
	}

	public IntPoint get(int i) {
		return points.get(i);
	}

	public void remove(int i) {
		points.remove(i);
	}
	
	public void clear() {
		points.clear();
	}
	
	public void reverse() {
		Collections.reverse(points);
	}
	
	public String toString() {
		return points.toString();
	}
	
	public List<IntPoint> getPoints() {
		return points;
	}

	@Override
	public Iterator<IntPoint> iterator() {
		return points.iterator();
	}
}
