package clipper.ui;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import javax.swing.SwingUtilities;

import clipper.ClipType;
import clipper.Clipper;
import clipper.IntPoint;
import clipper.PolyFillType;
import clipper.Polygon;
import clipper.internal.PolyType;
import clipper.ui.Presenter.View.Callback;
import clipper.ui.Presenter.View.Sample;

public class Presenter {
	public interface View {
		interface Callback { void execute(); }
		enum FillType { EVENODD, NONZERO }
		enum Operation { INTERSECTION, UNION, DIFFERENCE, XOR, NONE }
		enum Sample { CIRCLES, POLYGONS }
		
		void setSize(int width, int height);
		void setSubjectPolygons(List<Polygon> polygons);
		void setClipPolygons(List<Polygon> polygons);
		void setSolutionPolygons(List<Polygon> polygons);
		void setScale(long scale);
		void setAreas(double subjectArea, double clipArea, double intersectionArea, double unionArea, double calculatedUnion);

		void setUpdateCallback(Callback callback);
		
		Operation getOperation();
		FillType getFillType();
		int getCount();
		int getOffset();
		Sample getSample();
	}

	private static Random random = new Random();

	private int scale = 100;
	private int width = 900;
	private int height = 700;
	private View view;

	public Presenter(View view) {
		this.view = view;
	}

	public void start() {
		view.setSize(width, height);

		refresh();
		
		view.setUpdateCallback(new Callback() {
			public void execute() {
				refresh();
			}
		});
	}
	
	private void refresh() {
		List<Polygon> solution = new ArrayList<Polygon>();
		List<Polygon> subjects = createRandomPolygons(view.getCount());
		List<Polygon> clip = createRandomPolygons(view.getCount());

		if(view.getSample() == Sample.CIRCLES) {
			subjects = getAustraliaPolygon();
		}
		
		PolyFillType fillType = getFillType();
		ClipType clipType = getClipType();
		
		Clipper c = new Clipper();
		if(clipType != null) {
			c.addPolygons(subjects, PolyType.ptSubject);
			c.addPolygons(clip, PolyType.ptClip);
			c.execute(clipType, solution, fillType, fillType);
		}
	
		solution = Clipper.offsetPolygons(solution, view.getOffset() * scale);
		
		List<Polygon> solution2 = new ArrayList<Polygon>();
		c.clear();
		c.addPolygons(subjects, PolyType.ptSubject);
		c.execute(ClipType.UNION, solution2, fillType, fillType);
		double subjectArea = getArea(solution2);
		c.clear();
		c.addPolygons(clip, PolyType.ptClip);
		c.execute(ClipType.UNION, solution2, fillType, fillType);
		double clipArea = getArea(solution2);
		c.addPolygons(subjects, PolyType.ptSubject);
		c.execute(ClipType.INTERSECTION, solution2, fillType, fillType);
		double intersectionArea = getArea(solution2);
		c.execute(ClipType.UNION, solution2, fillType, fillType);
		double unionArea = getArea(solution2);
		double calculatedUnion = subjectArea + clipArea - intersectionArea;
		
		view.setAreas(subjectArea, clipArea, intersectionArea, unionArea, calculatedUnion);

		view.setScale(scale);
		view.setSubjectPolygons(subjects);
		view.setClipPolygons(clip);
		view.setSolutionPolygons(solution);
	}

	private List<Polygon> getAustraliaPolygon() {
		InputStream inputStream = getClass().getResourceAsStream("/clipper/ui/resource/aust.bin");

		List<Polygon> polygons = new ArrayList<Polygon>();
		
		try { 
			int numPolygons = readInt(inputStream);
			for(int x = 0; x < numPolygons; x++) {
				Polygon polygon = new Polygon();
				int numVertices = readInt(inputStream);
				
				for(int y = 0; y < numVertices; y++) {
					float px = readFloat(inputStream);
					float py = readFloat(inputStream);
					polygon.add(new IntPoint((long)(px * 1.5) * 100, (long)(py * 1.5) * 90));
				}
				polygons.add(polygon);
			}
		} catch(IOException e) {
			e.printStackTrace();
			return Collections.emptyList();
		} finally {
			try {
				inputStream.close();
			} catch(IOException e) {
			}
		}
		
		return polygons;
	}

	private static int readInt(InputStream inputStream) throws IOException {
		int b1 = inputStream.read();
		int b2 = inputStream.read();
		int b3 = inputStream.read();
		int b4 = inputStream.read();
		
		if(b1 == -1 || b2 == -1 || b3 == -1 || b4 == -1) throw new IOException("End of file");
		
		int r = 0;
		r += b4;
		r <<= 8;
		r += b3;
		r <<= 8;
		r += b2;
		r <<= 8;
		r += b1;
		
		return r;
	}
	
	private static float readFloat(InputStream inputStream) throws IOException {
		return Float.intBitsToFloat(readInt(inputStream));
	}
	
	private ClipType getClipType() {
		switch(view.getOperation()) {
			case DIFFERENCE:
				return ClipType.DIFFERENCE;
			case INTERSECTION:
				return ClipType.INTERSECTION;
			case UNION:
				return ClipType.UNION;
			case XOR:
				return ClipType.XOR;
		}
		return null;
	}

	private PolyFillType getFillType() {
		switch(view.getFillType()) {
			case EVENODD:
				return PolyFillType.EVENODD;
			case NONZERO:
				return PolyFillType.NONZERO;
		}
		return PolyFillType.EVENODD;
	}

	private double getArea(List<Polygon> polygons) {
		double area = 0;
		for(Polygon polygon:polygons) {
			area += Clipper.area(polygon);
		}
		return area;
	}

	private List<Polygon> createRandomPolygons(int n) {
		List<Polygon> polygons = new ArrayList<Polygon>();
		
		if(view.getSample() == Sample.CIRCLES) {
			for (int x = 0; x < n; x++) {
				polygons.add(createRandomCircle());
			}
		} else {
			polygons.add(createRandomPolygon(n));
		}
		
		return polygons;
	}

	private Polygon createRandomPolygon(int n) {
		Polygon polygon = new Polygon();
		for (int i = 0; i < n; i++) {
			int x = random.nextInt(width * scale);
			int y = random.nextInt(height * scale);
			polygon.add(new IntPoint(x, y));
		}
		return polygon;
	}

	private Polygon createRandomCircle() {
		Polygon polygon = new Polygon();

		int r = random.nextInt(width / 8 * scale);
		int cx = random.nextInt(width * scale);
		int cy = random.nextInt(height * scale);

		for (double a = 0; a < 2 * Math.PI; a += 0.1) {
			double x = cx + r * Math.sin(a);
			double y = cy + r * Math.cos(a);

			polygon.add(new IntPoint((int) x, (int) y));
		}

		return polygon;
	}

	private Polygon createRandomSquare() {
		Polygon polygon = new Polygon();

		int length = random.nextInt(width / 2);
		int cx = random.nextInt(width);
		int cy = random.nextInt(height);

		polygon.add(new IntPoint(cx, cy));
		polygon.add(new IntPoint(cx + length, cy));
		polygon.add(new IntPoint(cx + length, cy + length));
		polygon.add(new IntPoint(cx, cy + length));

		return polygon;
	}

	public static void main(String[] args) {
		SwingUtilities.invokeLater(new Runnable() {
			public void run() {
				new Presenter(new GuiTest()).start();
			}
		});
	}
}
