package clipper;

import java.util.ArrayList;
import java.util.List;

import clipper.internal.EdgeSide;
import clipper.internal.LocalMinima;
import clipper.internal.OutPt;
import clipper.internal.PolyType;
import clipper.internal.TEdge;

/*******************************************************************************
 *                                                                              *
 * Author    :  Angus Johnson                                                   *
 * Version   :  5.1.6                                                           *
 * Date      :  23 May 2013                                                     *
 * Website   :  http://www.angusj.com                                           *
 * Copyright :  Angus Johnson 2010-2013                                         *
 *                                                                              *
 * License:                                                                     *
 * Use, modification & distribution is subject to Boost Software License Ver 1. *
 * http://www.boost.org/LICENSE_1_0.txt                                         *
 *                                                                              *
 * Attributions:                                                                *
 * The code in this library is an extension of Bala Vatti's clipping algorithm: *
 * "A generic solution to polygon clipping"                                     *
 * Communications of the ACM, Vol 35, Issue 7 (July 1992) pp 56-63.             *
 * http://portal.acm.org/citation.cfm?id=129906                                 *
 *                                                                              *
 * Computer graphics and geometric modeling: implementation and algorithms      *
 * By Max K. Agoston                                                            *
 * Springer; 1 edition (January 4, 2005)                                        *
 * http://books.google.com/books?q=vatti+clipping+agoston                       *
 *                                                                              *
 * See also:                                                                    *
 * "Polygon Offsetting by Computing Winding Numbers"                            *
 * Paper no. DETC2005-85513 pp. 565-575                                         *
 * ASME 2005 International Design Engineering Technical Conferences             *
 * and Computers and Information in Engineering Conference (IDETC/CIE2005)      *
 * September 24-28, 2005 , Long Beach, California, USA                          *
 * http://www.me.berkeley.edu/~mcmains/pubs/DAC05OffsetPolygon.pdf              *
 *                                                                              *
 *******************************************************************************/

// By far the most widely used winding rules for polygon filling are
// EvenOdd & NonZero (GDI, GDI+, XLib, OpenGL, Cairo, AGG, Quartz, SVG, Gr32)
// Others rules include Positive, Negative and ABS_GTR_EQ_TWO (only in OpenGL)
// see http://glprogramming.com/red/chapter11.html

public class ClipperBase {
	protected final double horizontal = -3.4E+38;
	static final long loRange = 0x3FFFFFFF;
	static final long hiRange = 0x3FFFFFFFFFFFFFFFL;

	LocalMinima m_MinimaList;
	LocalMinima m_CurrentLM;
	List<List<TEdge>> m_edges = new ArrayList<List<TEdge>>();
	boolean m_UseFullRange;

	protected void clear() {
		m_MinimaList = null;
		m_CurrentLM = null;
		m_UseFullRange = false;
		m_edges.clear();
	}
	
	protected static boolean PointsEqual(IntPoint pt1, IntPoint pt2) {
		return (pt1.x == pt2.x && pt1.y == pt2.y);
	}

	boolean PointIsVertex(IntPoint pt, OutPt pp) {
		OutPt pp2 = pp;
		do {
			if(PointsEqual(pp2.pt, pt))
				return true;
			pp2 = pp2.next;
		} while(pp2 != pp);
		return false;
	}

	boolean PointOnLineSegment(IntPoint pt, IntPoint linePt1, IntPoint linePt2, boolean UseFulllongRange) {
		if(UseFulllongRange)
			return ((pt.x == linePt1.x) && (pt.y == linePt1.y)) || ((pt.x == linePt2.x) && (pt.y == linePt2.y))
					|| (((pt.x > linePt1.x) == (pt.x < linePt2.x)) && ((pt.y > linePt1.y) == (pt.y < linePt2.y)) && ((Int128.multiply((pt.x - linePt1.x), (linePt2.y - linePt1.y)) == Int128.multiply((linePt2.x - linePt1.x), (pt.y - linePt1.y)))));
		else
			return ((pt.x == linePt1.x) && (pt.y == linePt1.y)) || ((pt.x == linePt2.x) && (pt.y == linePt2.y))
					|| (((pt.x > linePt1.x) == (pt.x < linePt2.x)) && ((pt.y > linePt1.y) == (pt.y < linePt2.y)) && ((pt.x - linePt1.x) * (linePt2.y - linePt1.y) == (linePt2.x - linePt1.x) * (pt.y - linePt1.y)));
	}

	boolean PointOnPolygon(IntPoint pt, OutPt pp, boolean UseFulllongRange) {
		OutPt pp2 = pp;
		while(true) {
			if(PointOnLineSegment(pt, pp2.pt, pp2.next.pt, UseFulllongRange))
				return true;
			pp2 = pp2.next;
			if(pp2 == pp)
				break;
		}
		return false;
	}

	boolean PointInPolygon(IntPoint pt, OutPt pp, boolean UseFulllongRange) {
		OutPt pp2 = pp;
		boolean result = false;
		if(UseFulllongRange) {
			do {
				if((((pp2.pt.y <= pt.y) && (pt.y < pp2.prev.pt.y)) || ((pp2.prev.pt.y <= pt.y) && (pt.y < pp2.pt.y))) && new Int128(pt.x - pp2.pt.x).compareTo(Int128.multiply(pp2.prev.pt.x - pp2.pt.x, pt.y - pp2.pt.y).divide(new Int128(pp2.prev.pt.y - pp2.pt.y))) < 0)
					result = !result;
				pp2 = pp2.next;
			} while(pp2 != pp);
		} else {
			do {
				if((((pp2.pt.y <= pt.y) && (pt.y < pp2.prev.pt.y)) || ((pp2.prev.pt.y <= pt.y) && (pt.y < pp2.pt.y))) && (pt.x - pp2.pt.x < (pp2.prev.pt.x - pp2.pt.x) * (pt.y - pp2.pt.y) / (pp2.prev.pt.y - pp2.pt.y)))
					result = !result;
				pp2 = pp2.next;
			} while(pp2 != pp);
		}
		return result;
	}

	static boolean SlopesEqual(TEdge e1, TEdge e2, boolean UseFullRange) {
		if(UseFullRange) {
			return Int128.multiply(e1.deltaY, e2.deltaX).equals(Int128.multiply(e1.deltaX, e2.deltaY));
		} else {
			return (long) (e1.deltaY) * (e2.deltaX) == (long) (e1.deltaX) * (e2.deltaY);
		}
	}

	protected static boolean SlopesEqual(IntPoint pt1, IntPoint pt2, IntPoint pt3, boolean UseFullRange) {
		if(UseFullRange)
			return Int128.multiply(pt1.y - pt2.y, pt2.x - pt3.x).equals(Int128.multiply(pt1.x - pt2.x, pt2.y - pt3.y));
		else
			return (long) (pt1.y - pt2.y) * (pt2.x - pt3.x) - (long) (pt1.x - pt2.x) * (pt2.y - pt3.y) == 0;
	}

	protected static boolean SlopesEqual(IntPoint pt1, IntPoint pt2, IntPoint pt3, IntPoint pt4, boolean UseFullRange) {
		if(UseFullRange)
			return Int128.multiply(pt1.y - pt2.y, pt3.x - pt4.x).equals(Int128.multiply(pt1.x - pt2.x, pt3.y - pt4.y));
		else
			return (long) (pt1.y - pt2.y) * (pt3.x - pt4.x) - (long) (pt1.x - pt2.x) * (pt3.y - pt4.y) == 0;
	}

	ClipperBase() // constructor (nb: no external instantiation)
	{
		m_MinimaList = null;
		m_CurrentLM = null;
		m_UseFullRange = false;
	}

	public boolean addPolygons(List<Polygon> ppg, PolyType polyType) {
		boolean result = false;
		for(int i = 0; i < ppg.size(); ++i)
			if(addPolygon(ppg.get(i), polyType))
				result = true;
		return result;
	}

	long RangeTest(IntPoint pt, long maxrange) {
		if(pt.x > maxrange) {
			if(pt.x > hiRange)
				throw new ClipperException("Coordinate exceeds range bounds");
			else
				return hiRange;
		}
		if(pt.y > maxrange) {
			if(pt.y > hiRange)
				throw new ClipperException("Coordinate exceeds range bounds");
			else
				return hiRange;
		}
		return maxrange;
	}

	public boolean addPolygon(Polygon pg, PolyType polyType) {
		int len = pg.size();
		if(len < 3)
			return false;
		long maxVal;
		if(m_UseFullRange)
			maxVal = hiRange;
		else
			maxVal = loRange;
		maxVal = RangeTest(pg.get(0), maxVal);

		List<IntPoint> p = new ArrayList<IntPoint>(len);
		p.add(new IntPoint(pg.get(0).x, pg.get(0).y));
		int j = 0;
		for(int i = 1; i < len; ++i) {
			maxVal = RangeTest(pg.get(i), maxVal);
			if(PointsEqual(p.get(j), pg.get(i)))
				continue;
			else if(j > 0 && SlopesEqual(p.get(j - 1), p.get(j), pg.get(i), maxVal == hiRange)) {
				if(PointsEqual(p.get(j - 1), pg.get(i)))
					j--;
			} else
				j++;
			if(j < p.size())
				p.set(j, pg.get(i));
			else
				p.add(new IntPoint(pg.get(i).x, pg.get(i).y));
		}
		if(j < 2)
			return false;
		m_UseFullRange = maxVal == hiRange;

		len = j + 1;
		while(len > 2) {
			// nb: test for point equality before testing slopes ...
			if(PointsEqual(p.get(j), p.get(0)))
				j--;
			else if(PointsEqual(p.get(0), p.get(1)) || SlopesEqual(p.get(j), p.get(0), p.get(1), m_UseFullRange))
				p.set(0, p.get(j--));
			else if(SlopesEqual(p.get(j - 1), p.get(j), p.get(0), m_UseFullRange))
				j--;
			else if(SlopesEqual(p.get(0), p.get(1), p.get(2), m_UseFullRange)) {
				for(int i = 2; i <= j; ++i)
					p.set(i - 1, p.get(i));
				j--;
			} else
				break;
			len--;
		}
		if(len < 3)
			return false;

		// create a new edge array ...
		List<TEdge> edges = new ArrayList<TEdge>(len);
		for(int i = 0; i < len; i++)
			edges.add(new TEdge());
		m_edges.add(edges);

		// convert vertices to a double-linked-list of edges and initialize ...
		edges.get(0).xcurr = p.get(0).x;
		edges.get(0).ycurr = p.get(0).y;
		InitEdge(edges.get(len - 1), edges.get(0), edges.get(len - 2), p.get(len - 1), polyType);
		for(int i = len - 2; i > 0; --i)
			InitEdge(edges.get(i), edges.get(i + 1), edges.get(i - 1), p.get(i), polyType);
		InitEdge(edges.get(0), edges.get(1), edges.get(len - 1), p.get(0), polyType);

		// reset xcurr & ycurr and find 'eHighest' (given the Y axis coordinates
		// increase downward so the 'highest' edge will have the smallest ytop) ...
		TEdge e = edges.get(0);
		TEdge eHighest = e;
		do {
			e.xcurr = e.xbot;
			e.ycurr = e.ybot;
			if(e.ytop < eHighest.ytop)
				eHighest = e;
			e = e.next;
		} while(e != edges.get(0));

		// make sure eHighest is positioned so the following loop works safely ...
		if(eHighest.windDelta > 0)
			eHighest = eHighest.next;
		if(eHighest.dx == horizontal)
			eHighest = eHighest.next;

		// finally insert each local minima ...
		e = eHighest;
		do {
			e = AddBoundsToLML(e);
		} while(e != eHighest);
		return true;
	}

	private void InitEdge(TEdge e, TEdge eNext, TEdge ePrev, IntPoint pt, PolyType polyType) {
		e.next = eNext;
		e.prev = ePrev;
		e.xcurr = pt.x;
		e.ycurr = pt.y;
		if(e.ycurr >= e.next.ycurr) {
			e.xbot = e.xcurr;
			e.ybot = e.ycurr;
			e.xtop = e.next.xcurr;
			e.ytop = e.next.ycurr;
			e.windDelta = 1;
		} else {
			e.xtop = e.xcurr;
			e.ytop = e.ycurr;
			e.xbot = e.next.xcurr;
			e.ybot = e.next.ycurr;
			e.windDelta = -1;
		}
		SetDx(e);
		e.polyType = polyType;
		e.outIdx = -1;
	}

	private void SetDx(TEdge e) {
		e.deltaX = (e.xtop - e.xbot);
		e.deltaY = (e.ytop - e.ybot);
		if(e.deltaY == 0)
			e.dx = horizontal;
		else
			e.dx = (double) (e.deltaX) / (e.deltaY);
	}

	// ---------------------------------------------------------------------------

	TEdge AddBoundsToLML(TEdge e) {
		// Starting at the top of one bound we progress to the bottom where there's
		// a local minima. We then go to the top of the next bound. These two bounds
		// form the left and right (or right and left) bounds of the local minima.
		e.nextInLML = null;
		e = e.next;
		for(;;) {
			if(e.dx == horizontal) {
				// nb: proceed through horizontals when approaching from their right,
				// but break on horizontal minima if approaching from their left.
				// This ensures 'local minima' are always on the left of horizontals.
				if(e.next.ytop < e.ytop && e.next.xbot > e.prev.xbot)
					break;
				if(e.xtop != e.prev.xbot)
					SwapX(e);
				e.nextInLML = e.prev;
			} else if(e.ycurr == e.prev.ycurr)
				break;
			else
				e.nextInLML = e.prev;
			e = e.next;
		}

		// e and e.prev are now at a local minima ...
		LocalMinima newLm = new LocalMinima();
		newLm.next = null;
		newLm.y = e.prev.ybot;

		if(e.dx == horizontal) // horizontal edges never start a left bound
		{
			if(e.xbot != e.prev.xbot)
				SwapX(e);
			newLm.leftBound = e.prev;
			newLm.rightBound = e;
		} else if(e.dx < e.prev.dx) {
			newLm.leftBound = e.prev;
			newLm.rightBound = e;
		} else {
			newLm.leftBound = e;
			newLm.rightBound = e.prev;
		}
		newLm.leftBound.side = EdgeSide.esLeft;
		newLm.rightBound.side = EdgeSide.esRight;
		InsertLocalMinima(newLm);

		for(;;) {
			if(e.next.ytop == e.ytop && e.next.dx != horizontal)
				break;
			e.nextInLML = e.next;
			e = e.next;
			if(e.dx == horizontal && e.xbot != e.prev.xtop)
				SwapX(e);
		}
		return e.next;
	}

	private void InsertLocalMinima(LocalMinima newLm) {
		if(m_MinimaList == null) {
			m_MinimaList = newLm;
		} else if(newLm.y >= m_MinimaList.y) {
			newLm.next = m_MinimaList;
			m_MinimaList = newLm;
		} else {
			LocalMinima tmpLm = m_MinimaList;
			while(tmpLm.next != null && (newLm.y < tmpLm.next.y))
				tmpLm = tmpLm.next;
			newLm.next = tmpLm.next;
			tmpLm.next = newLm;
		}
	}

	protected void PopLocalMinima() {
		if(m_CurrentLM == null)
			return;
		m_CurrentLM = m_CurrentLM.next;
	}

	private void SwapX(TEdge e) {
		// swap horizontal edges' top and bottom x's so they follow the natural
		// progression of the bounds - ie so their xbots will align with the
		// adjoining lower edge. [Helpful in the ProcessHorizontal() method.]
		e.xcurr = e.xtop;
		e.xtop = e.xbot;
		e.xbot = e.xcurr;
	}

	protected void Reset() {
		m_CurrentLM = m_MinimaList;

		// reset all edges ...
		LocalMinima lm = m_MinimaList;
		while(lm != null) {
			TEdge e = lm.leftBound;
			while(e != null) {
				e.xcurr = e.xbot;
				e.ycurr = e.ybot;
				e.side = EdgeSide.esLeft;
				e.outIdx = -1;
				e = e.nextInLML;
			}
			e = lm.rightBound;
			while(e != null) {
				e.xcurr = e.xbot;
				e.ycurr = e.ybot;
				e.side = EdgeSide.esRight;
				e.outIdx = -1;
				e = e.nextInLML;
			}
			lm = lm.next;
		}
		return;
	}

	public IntRect getBounds() {
		IntRect result = new IntRect();
		LocalMinima lm = m_MinimaList;
		if(lm == null)
			return result;
		result.left = lm.leftBound.xbot;
		result.top = lm.leftBound.ybot;
		result.right = lm.leftBound.xbot;
		result.bottom = lm.leftBound.ybot;
		while(lm != null) {
			if(lm.leftBound.ybot > result.bottom)
				result.bottom = lm.leftBound.ybot;
			TEdge e = lm.leftBound;
			for(;;) {
				TEdge bottomE = e;
				while(e.nextInLML != null) {
					if(e.xbot < result.left)
						result.left = e.xbot;
					if(e.xbot > result.right)
						result.right = e.xbot;
					e = e.nextInLML;
				}
				if(e.xbot < result.left)
					result.left = e.xbot;
				if(e.xbot > result.right)
					result.right = e.xbot;
				if(e.xtop < result.left)
					result.left = e.xtop;
				if(e.xtop > result.right)
					result.right = e.xtop;
				if(e.ytop < result.top)
					result.top = e.ytop;

				if(bottomE == lm.leftBound)
					e = lm.rightBound;
				else
					break;
			}
			lm = lm.next;
		}
		return result;
	}
}