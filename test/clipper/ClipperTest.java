package clipper;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import clipper.internal.PolyType;

public class ClipperTest {
	@Test
	public void test() {
		List<Polygon> subj = new ArrayList<Polygon>();
		subj.add(new Polygon());
		subj.get(0).add(180, 200);
		subj.get(0).add(260, 200);
		subj.get(0).add(260, 150);
		subj.get(0).add(180, 150);
		
		subj.add(new Polygon());
		subj.get(1).add(215, 160);
		subj.get(1).add(230, 190);
		subj.get(1).add(200, 190);
		
		List<Polygon> clip = new ArrayList<Polygon>();
		clip.add(new Polygon());
		clip.get(0).add(190, 210);
		clip.get(0).add(240, 210);
		clip.get(0).add(240, 130);
		clip.get(0).add(190, 130);
		
		List<Polygon> solution = new ArrayList<Polygon>();
		
		Clipper c = new Clipper();
		c.addPolygons(subj, PolyType.ptSubject);
		c.addPolygons(clip, PolyType.ptClip);
		c.execute(ClipType.UNION, solution, PolyFillType.EVENODD, PolyFillType.EVENODD);
		
		System.out.println("SUBJ: " + subj);
		System.out.println("CLIP: " + clip);
		System.out.println("SOLUTION: " + solution);
		
		assertEquals(1, solution.size());
		assertEquals(12, solution.get(0).size());
	}
}