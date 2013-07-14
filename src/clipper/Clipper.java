package clipper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import clipper.internal.Direction;
import clipper.internal.EdgeSide;
import clipper.internal.HorzJoinRec;
import clipper.internal.IntersectNode;
import clipper.internal.JoinRec;
import clipper.internal.JoinType;
import clipper.internal.LocalMinima;
import clipper.internal.OutPt;
import clipper.internal.OutRec;
import clipper.internal.PolyType;
import clipper.internal.Protects;
import clipper.internal.Scanbeam;
import clipper.internal.TEdge;

public class Clipper extends ClipperBase {
	private List<OutRec> m_PolyOuts;
	private ClipType m_ClipType;
	private Scanbeam m_Scanbeam;
	private TEdge m_ActiveEdges;
	private TEdge m_SortedEdges;
	private IntersectNode m_IntersectNodes;
	private boolean m_ExecuteLocked;
	private PolyFillType m_ClipFillType;
	private PolyFillType m_SubjFillType;
	private List<JoinRec> m_Joins;
	private List<HorzJoinRec> m_HorizJoins;
	private boolean m_ReverseOutput;
	private boolean m_ForceSimple;
	private boolean m_UsingPolyTree;

	public Clipper() {
		m_Scanbeam = null;
		m_ActiveEdges = null;
		m_SortedEdges = null;
		m_IntersectNodes = null;
		m_ExecuteLocked = false;
		m_UsingPolyTree = false;
		m_PolyOuts = new ArrayList<OutRec>();
		m_Joins = new ArrayList<JoinRec>();
		m_HorizJoins = new ArrayList<HorzJoinRec>();
		m_ReverseOutput = false;
		m_ForceSimple = false;
	}

	protected void Reset() {
		super.Reset();
		m_Scanbeam = null;
		m_ActiveEdges = null;
		m_SortedEdges = null;
		m_PolyOuts.clear();
		LocalMinima lm = m_MinimaList;
		while(lm != null) {
			InsertScanbeam(lm.y);
			lm = lm.next;
		}
	}
	
	public void clear() {
		m_PolyOuts.clear();
		super.clear();
	}

	public boolean getReverseSolution() {
		return m_ReverseOutput;
	}

	public void setReverseSolution(boolean reverseSolution) {
		m_ReverseOutput = reverseSolution;
	}

	public boolean getForceSimple() {
		return m_ForceSimple;
	}

	public void setForceSimple(boolean forceSimple) {
		m_ForceSimple = forceSimple;
	}

	private void InsertScanbeam(long Y) {
		if(m_Scanbeam == null) {
			m_Scanbeam = new Scanbeam();
			m_Scanbeam.next = null;
			m_Scanbeam.Y = Y;
		} else if(Y > m_Scanbeam.Y) {
			Scanbeam newSb = new Scanbeam();
			newSb.Y = Y;
			newSb.next = m_Scanbeam;
			m_Scanbeam = newSb;
		} else {
			Scanbeam sb2 = m_Scanbeam;
			while(sb2.next != null && (Y <= sb2.next.Y))
				sb2 = sb2.next;
			if(Y == sb2.Y)
				return; // ie ignores duplicates
			Scanbeam newSb = new Scanbeam();
			newSb.Y = Y;
			newSb.next = sb2.next;
			sb2.next = newSb;
		}
	}

	public void execute(ClipType clipType, List<Polygon> solution, PolyFillType subjFillType, PolyFillType clipFillType) {
		if(m_ExecuteLocked)
			throw new IllegalStateException("Execution locked");
		m_ExecuteLocked = true;
		try {
			solution.clear();
			m_SubjFillType = subjFillType;
			m_ClipFillType = clipFillType;
			m_ClipType = clipType;
			m_UsingPolyTree = false;
			ExecuteInternal();
			// build the return polygons ...
			BuildResult(solution);
		} finally {
			m_ExecuteLocked = false;
		}
	}

	public void execute(ClipType clipType, PolyTree polytree, PolyFillType subjFillType, PolyFillType clipFillType) {
		if(m_ExecuteLocked)
			throw new IllegalStateException("Execution locked");
		m_ExecuteLocked = true;
		try {
			m_SubjFillType = subjFillType;
			m_ClipFillType = clipFillType;
			m_ClipType = clipType;
			m_UsingPolyTree = true;
			ExecuteInternal();
			// build the return polygons ...
			BuildResult(polytree);
		} finally {
			m_ExecuteLocked = false;
		}
	}

	public void execute(ClipType clipType, List<Polygon> solution) {
		execute(clipType, solution, PolyFillType.EVENODD, PolyFillType.EVENODD);
	}

	public void execute(ClipType clipType, PolyTree polytree) {
		execute(clipType, polytree, PolyFillType.EVENODD, PolyFillType.EVENODD);
	}

	void FixHoleLinkage(OutRec outRec) {
		// skip if an outermost polygon or
		// already already points to the correct FirstLeft ...
		if(outRec.firstLeft == null || (outRec.isHole != outRec.firstLeft.isHole && outRec.firstLeft.pts != null))
			return;

		OutRec orfl = outRec.firstLeft;
		while(orfl != null && ((orfl.isHole == outRec.isHole) || orfl.pts == null))
			orfl = orfl.firstLeft;
		outRec.firstLeft = orfl;
	}

	private void ExecuteInternal() {
		try {
			Reset();
			if(m_CurrentLM == null)
				return;
			long botY = PopScanbeam();
			do {
				InsertLocalMinimaIntoAEL(botY);
				m_HorizJoins.clear();
				ProcessHorizontals();
				long topY = PopScanbeam();
				ProcessIntersections(botY, topY);
				ProcessEdgesAtTopOfScanbeam(topY);
				botY = topY;
			} while(m_Scanbeam != null || m_CurrentLM != null);
			
			// tidy up output polygons and fix orientations where necessary ...
			for(int i = 0; i < m_PolyOuts.size(); i++) {
				OutRec outRec = m_PolyOuts.get(i);
				if(outRec.pts == null)
					continue;
				FixupOutPolygon(outRec);
				if(outRec.pts == null)
					continue;
				if((outRec.isHole ^ m_ReverseOutput) == (Area(outRec, m_UseFullRange) > 0))
					ReversePolyPtLinks(outRec.pts);
			}
			JoinCommonEdges();
			if(m_ForceSimple)
				DoSimplePolygons();
		} finally {
			m_Joins.clear();
			m_HorizJoins.clear();
		}
	}

	private long PopScanbeam() {
		long Y = m_Scanbeam.Y;
		m_Scanbeam = m_Scanbeam.next;
		return Y;
	}

	private void AddJoin(TEdge e1, TEdge e2, int e1OutIdx, int e2OutIdx) {
		JoinRec jr = new JoinRec();
		if(e1OutIdx >= 0)
			jr.poly1Idx = e1OutIdx;
		else
			jr.poly1Idx = e1.outIdx;
		jr.pt1a = new IntPoint(e1.xcurr, e1.ycurr);
		jr.pt1b = new IntPoint(e1.xtop, e1.ytop);
		if(e2OutIdx >= 0)
			jr.poly2Idx = e2OutIdx;
		else
			jr.poly2Idx = e2.outIdx;
		jr.pt2a = new IntPoint(e2.xcurr, e2.ycurr);
		jr.pt2b = new IntPoint(e2.xtop, e2.ytop);
		m_Joins.add(jr);
	}

	private void AddHorzJoin(TEdge e, int idx) {
		HorzJoinRec hj = new HorzJoinRec();
		hj.edge = e;
		hj.savedIdx = idx;
		m_HorizJoins.add(hj);
	}

	private void InsertLocalMinimaIntoAEL(long botY) {
		while(m_CurrentLM != null && (m_CurrentLM.y == botY)) {
			TEdge lb = m_CurrentLM.leftBound;
			TEdge rb = m_CurrentLM.rightBound;

			InsertEdgeIntoAEL(lb);
			InsertScanbeam(lb.ytop);
			InsertEdgeIntoAEL(rb);

			if(IsEvenOddFillType(lb)) {
				lb.windDelta = 1;
				rb.windDelta = 1;
			} else {
				rb.windDelta = -lb.windDelta;
			}
			SetWindingCount(lb);
			rb.windCnt = lb.windCnt;
			rb.windCnt2 = lb.windCnt2;
			
			if(rb.dx == horizontal) {
				// nb: only rightbounds can have a horizontal bottom edge
				AddEdgeToSEL(rb);
				InsertScanbeam(rb.nextInLML.ytop);
			} else
				InsertScanbeam(rb.ytop);

			if(IsContributing(lb))
				AddLocalMinPoly(lb, rb, new IntPoint(lb.xcurr, m_CurrentLM.y));

			// if any output polygons share an edge, they'll need joining later ...
			if(rb.outIdx >= 0 && rb.dx == horizontal) {
				for(int i = 0; i < m_HorizJoins.size(); i++) {
					final AtomicReference<IntPoint> pt1 = new AtomicReference<IntPoint>(new IntPoint());
					final AtomicReference<IntPoint> pt2 = new AtomicReference<IntPoint>(new IntPoint());

					HorzJoinRec hj = m_HorizJoins.get(i);
					// if horizontals rb and hj.edge overlap, flag for joining later ...

					IntPoint pt1a = new IntPoint(hj.edge.xbot, hj.edge.ybot);
					IntPoint pt1b = new IntPoint(hj.edge.xtop, hj.edge.ytop);
					IntPoint pt2a = new IntPoint(rb.xbot, rb.ybot);
					IntPoint pt2b = new IntPoint(rb.xtop, rb.ytop);

					if(GetOverlapSegment(pt1a, pt1b, pt2a, pt2b, pt1, pt2)) {
						AddJoin(hj.edge, rb, hj.savedIdx, -1);
					}
				}
			}

			if(lb.nextInAEL != rb) {
				if(rb.outIdx >= 0 && rb.prevInAEL.outIdx >= 0 && SlopesEqual(rb.prevInAEL, rb, m_UseFullRange))
					AddJoin(rb, rb.prevInAEL, -1, -1);

				TEdge e = lb.nextInAEL;
				IntPoint pt = new IntPoint(lb.xcurr, lb.ycurr);
				while(e != rb) {
					if(e == null)
						throw new ClipperException("InsertLocalMinimaIntoAEL: missing rightbound!");
					// nb: For calculating winding counts etc, IntersectEdges() assumes
					// that param1 will be to the right of param2 ABOVE the intersection ...
					IntersectEdges(rb, e, pt, Protects.ipNone); // order important here
					e = e.nextInAEL;
				}
			}
			PopLocalMinima();
		}
	}

	private void InsertEdgeIntoAEL(TEdge edge) {
		edge.prevInAEL = null;
		edge.nextInAEL = null;
		if(m_ActiveEdges == null) {
			m_ActiveEdges = edge;
		} else if(E2InsertsBeforeE1(m_ActiveEdges, edge)) {
			edge.nextInAEL = m_ActiveEdges;
			m_ActiveEdges.prevInAEL = edge;
			m_ActiveEdges = edge;
		} else {
			TEdge e = m_ActiveEdges;
			while(e.nextInAEL != null && !E2InsertsBeforeE1(e.nextInAEL, edge))
				e = e.nextInAEL;
			edge.nextInAEL = e.nextInAEL;
			if(e.nextInAEL != null)
				e.nextInAEL.prevInAEL = edge;
			edge.prevInAEL = e;
			e.nextInAEL = edge;
		}
	}

	private boolean E2InsertsBeforeE1(TEdge e1, TEdge e2) {
		if(e2.xcurr == e1.xcurr) {
			if(e2.ytop > e1.ytop)
				return e2.xtop < TopX(e1, e2.ytop);
			else
				return e1.xtop > TopX(e2, e1.ytop);
		} else
			return e2.xcurr < e1.xcurr;
	}

	private boolean IsEvenOddFillType(TEdge edge) {
		if(edge.polyType == PolyType.ptSubject)
			return m_SubjFillType == PolyFillType.EVENODD;
		else
			return m_ClipFillType == PolyFillType.EVENODD;
	}

	private boolean IsEvenOddAltFillType(TEdge edge) {
		if(edge.polyType == PolyType.ptSubject)
			return m_ClipFillType == PolyFillType.EVENODD;
		else
			return m_SubjFillType == PolyFillType.EVENODD;
	}

	private boolean IsContributing(TEdge edge) {
		PolyFillType pft, pft2;
		if(edge.polyType == PolyType.ptSubject) {
			pft = m_SubjFillType;
			pft2 = m_ClipFillType;
		} else {
			pft = m_ClipFillType;
			pft2 = m_SubjFillType;
		}

		switch(pft) {
		case EVENODD:
		case NONZERO:
			if(Math.abs(edge.windCnt) != 1)
				return false;
			break;
		case POSITIVE:
			if(edge.windCnt != 1)
				return false;
			break;
		default: // PolyFillType.pftNegative
			if(edge.windCnt != -1)
				return false;
			break;
		}

		switch(m_ClipType) {
		case INTERSECTION:
			switch(pft2) {
			case EVENODD:
			case NONZERO:
				return (edge.windCnt2 != 0);
			case POSITIVE:
				return (edge.windCnt2 > 0);
			default:
				return (edge.windCnt2 < 0);
			}
		case UNION:
			switch(pft2) {
			case EVENODD:
			case NONZERO:
				return (edge.windCnt2 == 0);
			case POSITIVE:
				return (edge.windCnt2 <= 0);
			default:
				return (edge.windCnt2 >= 0);
			}
		case DIFFERENCE:
			if(edge.polyType == PolyType.ptSubject)
				switch(pft2) {
				case EVENODD:
				case NONZERO:
					return (edge.windCnt2 == 0);
				case POSITIVE:
					return (edge.windCnt2 <= 0);
				default:
					return (edge.windCnt2 >= 0);
				}
			else
				switch(pft2) {
				case EVENODD:
				case NONZERO:
					return (edge.windCnt2 != 0);
				case POSITIVE:
					return (edge.windCnt2 > 0);
				default:
					return (edge.windCnt2 < 0);
				}
		}
		return true;
	}

	private void SetWindingCount(TEdge edge) {
		TEdge e = edge.prevInAEL;
		// find the edge of the same polytype that immediately preceeds 'edge' in AEL
		while(e != null && e.polyType != edge.polyType)
			e = e.prevInAEL;
		if(e == null) {
			edge.windCnt = edge.windDelta;
			edge.windCnt2 = 0;
			e = m_ActiveEdges; // ie get ready to calc windCnt2
		} else if(IsEvenOddFillType(edge)) {
			// even-odd filling ...
			edge.windCnt = 1;
			edge.windCnt2 = e.windCnt2;
			e = e.nextInAEL; // ie get ready to calc windCnt2
		} else {
			// nonZero filling ...
			if(e.windCnt * e.windDelta < 0) {
				if(Math.abs(e.windCnt) > 1) {
					if(e.windDelta * edge.windDelta < 0)
						edge.windCnt = e.windCnt;
					else
						edge.windCnt = e.windCnt + edge.windDelta;
				} else
					edge.windCnt = e.windCnt + e.windDelta + edge.windDelta;
			} else {
				if(Math.abs(e.windCnt) > 1 && e.windDelta * edge.windDelta < 0)
					edge.windCnt = e.windCnt;
				else if(e.windCnt + edge.windDelta == 0)
					edge.windCnt = e.windCnt;
				else
					edge.windCnt = e.windCnt + edge.windDelta;
			}
			edge.windCnt2 = e.windCnt2;
			e = e.nextInAEL; // ie get ready to calc windCnt2
		}

		// update windCnt2 ...
		if(IsEvenOddAltFillType(edge)) {
			// even-odd filling ...
			while(e != edge) {
				edge.windCnt2 = (edge.windCnt2 == 0) ? 1 : 0;
				e = e.nextInAEL;
			}
		} else {
			// nonZero filling ...
			while(e != edge) {
				edge.windCnt2 += e.windDelta;
				e = e.nextInAEL;
			}
		}
	}

	private void AddEdgeToSEL(TEdge edge) {
		// SEL pointers in PEdge are reused to build a list of horizontal edges.
		// However, we don't need to worry about order with horizontal edge processing.
		if(m_SortedEdges == null) {
			m_SortedEdges = edge;
			edge.prevInSEL = null;
			edge.nextInSEL = null;
		} else {
			edge.nextInSEL = m_SortedEdges;
			edge.prevInSEL = null;
			m_SortedEdges.prevInSEL = edge;
			m_SortedEdges = edge;
		}
	}

	private void CopyAELToSEL() {
		TEdge e = m_ActiveEdges;
		m_SortedEdges = e;
		while(e != null) {
			e.prevInSEL = e.prevInAEL;
			e.nextInSEL = e.nextInAEL;
			e = e.nextInAEL;
		}
	}

	private void SwapPositionsInAEL(TEdge edge1, TEdge edge2) {
		if(edge1.nextInAEL == edge2) {
			TEdge next = edge2.nextInAEL;
			if(next != null)
				next.prevInAEL = edge1;
			TEdge prev = edge1.prevInAEL;
			if(prev != null)
				prev.nextInAEL = edge2;
			edge2.prevInAEL = prev;
			edge2.nextInAEL = edge1;
			edge1.prevInAEL = edge2;
			edge1.nextInAEL = next;
		} else if(edge2.nextInAEL == edge1) {
			TEdge next = edge1.nextInAEL;
			if(next != null)
				next.prevInAEL = edge2;
			TEdge prev = edge2.prevInAEL;
			if(prev != null)
				prev.nextInAEL = edge1;
			edge1.prevInAEL = prev;
			edge1.nextInAEL = edge2;
			edge2.prevInAEL = edge1;
			edge2.nextInAEL = next;
		} else {
			TEdge next = edge1.nextInAEL;
			TEdge prev = edge1.prevInAEL;
			edge1.nextInAEL = edge2.nextInAEL;
			if(edge1.nextInAEL != null)
				edge1.nextInAEL.prevInAEL = edge1;
			edge1.prevInAEL = edge2.prevInAEL;
			if(edge1.prevInAEL != null)
				edge1.prevInAEL.nextInAEL = edge1;
			edge2.nextInAEL = next;
			if(edge2.nextInAEL != null)
				edge2.nextInAEL.prevInAEL = edge2;
			edge2.prevInAEL = prev;
			if(edge2.prevInAEL != null)
				edge2.prevInAEL.nextInAEL = edge2;
		}

		if(edge1.prevInAEL == null)
			m_ActiveEdges = edge1;
		else if(edge2.prevInAEL == null)
			m_ActiveEdges = edge2;
	}

	private void SwapPositionsInSEL(TEdge edge1, TEdge edge2) {
		if(edge1.nextInSEL == null && edge1.prevInSEL == null)
			return;
		if(edge2.nextInSEL == null && edge2.prevInSEL == null)
			return;

		if(edge1.nextInSEL == edge2) {
			TEdge next = edge2.nextInSEL;
			if(next != null)
				next.prevInSEL = edge1;
			TEdge prev = edge1.prevInSEL;
			if(prev != null)
				prev.nextInSEL = edge2;
			edge2.prevInSEL = prev;
			edge2.nextInSEL = edge1;
			edge1.prevInSEL = edge2;
			edge1.nextInSEL = next;
		} else if(edge2.nextInSEL == edge1) {
			TEdge next = edge1.nextInSEL;
			if(next != null)
				next.prevInSEL = edge2;
			TEdge prev = edge2.prevInSEL;
			if(prev != null)
				prev.nextInSEL = edge1;
			edge1.prevInSEL = prev;
			edge1.nextInSEL = edge2;
			edge2.prevInSEL = edge1;
			edge2.nextInSEL = next;
		} else {
			TEdge next = edge1.nextInSEL;
			TEdge prev = edge1.prevInSEL;
			edge1.nextInSEL = edge2.nextInSEL;
			if(edge1.nextInSEL != null)
				edge1.nextInSEL.prevInSEL = edge1;
			edge1.prevInSEL = edge2.prevInSEL;
			if(edge1.prevInSEL != null)
				edge1.prevInSEL.nextInSEL = edge1;
			edge2.nextInSEL = next;
			if(edge2.nextInSEL != null)
				edge2.nextInSEL.prevInSEL = edge2;
			edge2.prevInSEL = prev;
			if(edge2.prevInSEL != null)
				edge2.prevInSEL.nextInSEL = edge2;
		}

		if(edge1.prevInSEL == null)
			m_SortedEdges = edge1;
		else if(edge2.prevInSEL == null)
			m_SortedEdges = edge2;
	}

	private void AddLocalMaxPoly(TEdge e1, TEdge e2, IntPoint pt) {
		AddOutPt(e1, pt);
		if(e1.outIdx == e2.outIdx) {
			e1.outIdx = -1;
			e2.outIdx = -1;
		} else if(e1.outIdx < e2.outIdx)
			AppendPolygon(e1, e2);
		else
			AppendPolygon(e2, e1);
	}

	private void AddLocalMinPoly(TEdge e1, TEdge e2, IntPoint pt) {
		TEdge e, prevE;
		if(e2.dx == horizontal || (e1.dx > e2.dx)) {
			AddOutPt(e1, pt);
			e2.outIdx = e1.outIdx;
			
			e1.side = EdgeSide.esLeft;
			e2.side = EdgeSide.esRight;
			e = e1;
			if(e.prevInAEL == e2)
				prevE = e2.prevInAEL;
			else
				prevE = e.prevInAEL;
		} else {
			AddOutPt(e2, pt);
			e1.outIdx = e2.outIdx;
			e1.side = EdgeSide.esRight;
			e2.side = EdgeSide.esLeft;
			e = e2;
			if(e.prevInAEL == e1)
				prevE = e1.prevInAEL;
			else
				prevE = e.prevInAEL;
		}

		if(prevE != null && prevE.outIdx >= 0 && (TopX(prevE, pt.y) == TopX(e, pt.y)) && SlopesEqual(e, prevE, m_UseFullRange)) {
			AddJoin(e, prevE, -1, -1);
		}

	}

	private OutRec CreateOutRec() {
		OutRec result = new OutRec();
		result.idx = -1;
		result.isHole = false;
		result.firstLeft = null;
		result.pts = null;
		result.bottomPt = null;
		result.polyNode = null;
		m_PolyOuts.add(result);
		result.idx = m_PolyOuts.size() - 1;
		return result;
	}

	private void AddOutPt(TEdge e, IntPoint pt) {
		boolean ToFront = (e.side == EdgeSide.esLeft);
		if(e.outIdx < 0) {
			OutRec outRec = CreateOutRec();
			e.outIdx = outRec.idx;
			OutPt op = new OutPt();
			outRec.pts = op;
			op.pt = pt;
			op.idx = outRec.idx;
			op.next = op;
			op.prev = op;
			SetHoleState(e, outRec);
		} else {
			OutRec outRec = m_PolyOuts.get(e.outIdx);
			OutPt op = outRec.pts, op2;
			if(ToFront && PointsEqual(pt, op.pt) || (!ToFront && PointsEqual(pt, op.prev.pt)))
				return;

			op2 = new OutPt();
			op2.pt = pt;
			op2.idx = outRec.idx;
			op2.next = op;
			op2.prev = op.prev;
			op2.prev.next = op2;
			op.prev = op2;
			if(ToFront)
				outRec.pts = op2;
		}
	}

	private boolean GetOverlapSegment(IntPoint pt1a, IntPoint pt1b, IntPoint pt2a, IntPoint pt2b, final AtomicReference<IntPoint> pt1, final AtomicReference<IntPoint> pt2) {
		// precondition: segments are colinear.
		if(Math.abs(pt1a.x - pt1b.x) > Math.abs(pt1a.y - pt1b.y)) {
			if(pt1a.x > pt1b.x) {
				IntPoint t = pt1a;
				pt1a = pt1b;
				pt1b = t;
			}
			if(pt2a.x > pt2b.x) {
				IntPoint t = pt2a;
				pt2a = pt2b;
				pt2b = t;
			}
			if(pt1a.x > pt2a.x)
				pt1.set(pt1a);
			else
				pt1.set(pt2a);
			if(pt1b.x < pt2b.x)
				pt2.set(pt1b);
			else
				pt2.set(pt2b);
			return pt1.get().x < pt2.get().x;
		} else {
			if(pt1a.y < pt1b.y) {
				IntPoint t = pt1a;
				pt1a = pt1b;
				pt1b = t;
			}
			if(pt2a.y < pt2b.y) {
				IntPoint t = pt2a;
				pt2a = pt2b;
				pt2b = t;
			}
			if(pt1a.y < pt2a.y)
				pt1.set(pt1a);
			else
				pt1.set(pt2a);
			if(pt1b.y > pt2b.y)
				pt2.set(pt1b);
			else
				pt2.set(pt2b);
			return pt1.get().y > pt2.get().y;
		}
	}

	private boolean FindSegment(final AtomicReference<OutPt> pp, boolean UseFullInt64Range, final AtomicReference<IntPoint> pt1, final AtomicReference<IntPoint> pt2) {
		if(pp.get() == null)
			return false;
		OutPt pp2 = pp.get();
		IntPoint pt1a = new IntPoint(pt1.get());
		IntPoint pt2a = new IntPoint(pt2.get());
		do {
			if(SlopesEqual(pt1a, pt2a, pp.get().pt, pp.get().prev.pt, UseFullInt64Range)
				&& SlopesEqual(pt1a, pt2a, pp.get().pt, UseFullInt64Range)
				&& GetOverlapSegment(pt1a, pt2a, pp.get().pt, pp.get().prev.pt, pt1, pt2))
				
				return true;
			pp.set(pp.get().next);
		} while(pp.get() != pp2);
		return false;
	}

	boolean Pt3IsBetweenPt1AndPt2(IntPoint pt1, IntPoint pt2, IntPoint pt3) {
		if(PointsEqual(pt1, pt3) || PointsEqual(pt2, pt3))
			return true;
		else if(pt1.x != pt2.x)
			return (pt1.x < pt3.x) == (pt3.x < pt2.x);
		else
			return (pt1.y < pt3.y) == (pt3.y < pt2.y);
	}

	private OutPt InsertPolyPtBetween(OutPt p1, OutPt p2, IntPoint pt) {
		OutPt result = new OutPt();
		result.pt = pt;
		if(p2 == p1.next) {
			p1.next = result;
			p2.prev = result;
			result.next = p2;
			result.prev = p1;
		} else {
			p2.next = result;
			p1.prev = result;
			result.next = p1;
			result.prev = p2;
		}
		return result;
	}

	private void SetHoleState(TEdge e, OutRec outRec) {
		boolean isHole = false;
		TEdge e2 = e.prevInAEL;
		while(e2 != null) {
			if(e2.outIdx >= 0) {
				isHole = !isHole;
				if(outRec.firstLeft == null)
					outRec.firstLeft = m_PolyOuts.get(e2.outIdx);
			}
			e2 = e2.prevInAEL;
		}
		if(isHole)
			outRec.isHole = true;
	}

	private double GetDx(IntPoint pt1, IntPoint pt2) {
		if(pt1.y == pt2.y)
			return horizontal;
		else
			return (double) (pt2.x - pt1.x) / (pt2.y - pt1.y);
	}

	private boolean FirstIsBottomPt(OutPt btmPt1, OutPt btmPt2) {
		OutPt p = btmPt1.prev;
		while(PointsEqual(p.pt, btmPt1.pt) && (p != btmPt1))
			p = p.prev;
		double dx1p = Math.abs(GetDx(btmPt1.pt, p.pt));
		p = btmPt1.next;
		while(PointsEqual(p.pt, btmPt1.pt) && (p != btmPt1))
			p = p.next;
		double dx1n = Math.abs(GetDx(btmPt1.pt, p.pt));

		p = btmPt2.prev;
		while(PointsEqual(p.pt, btmPt2.pt) && (p != btmPt2))
			p = p.prev;
		double dx2p = Math.abs(GetDx(btmPt2.pt, p.pt));
		p = btmPt2.next;
		while(PointsEqual(p.pt, btmPt2.pt) && (p != btmPt2))
			p = p.next;
		double dx2n = Math.abs(GetDx(btmPt2.pt, p.pt));
		return (dx1p >= dx2p && dx1p >= dx2n) || (dx1n >= dx2p && dx1n >= dx2n);
	}

	private OutPt GetBottomPt(OutPt pp) {
		OutPt dups = null;
		OutPt p = pp.next;
		while(p != pp) {
			if(p.pt.y > pp.pt.y) {
				pp = p;
				dups = null;
			} else if(p.pt.y == pp.pt.y && p.pt.x <= pp.pt.x) {
				if(p.pt.x < pp.pt.x) {
					dups = null;
					pp = p;
				} else {
					if(p.next != pp && p.prev != pp)
						dups = p;
				}
			}
			p = p.next;
		}
		if(dups != null) {
			// there appears to be at least 2 vertices at bottomPt so ...
			while(dups != p) {
				if(!FirstIsBottomPt(p, dups))
					pp = dups;
				dups = dups.next;
				while(!PointsEqual(dups.pt, pp.pt))
					dups = dups.next;
			}
		}
		return pp;
	}

	private OutRec GetLowermostRec(OutRec outRec1, OutRec outRec2) {
		// work out which polygon fragment has the correct hole state ...
		if(outRec1.bottomPt == null)
			outRec1.bottomPt = GetBottomPt(outRec1.pts);
		if(outRec2.bottomPt == null)
			outRec2.bottomPt = GetBottomPt(outRec2.pts);
		OutPt bPt1 = outRec1.bottomPt;
		OutPt bPt2 = outRec2.bottomPt;
		if(bPt1.pt.y > bPt2.pt.y)
			return outRec1;
		else if(bPt1.pt.y < bPt2.pt.y)
			return outRec2;
		else if(bPt1.pt.x < bPt2.pt.x)
			return outRec1;
		else if(bPt1.pt.x > bPt2.pt.x)
			return outRec2;
		else if(bPt1.next == bPt1)
			return outRec2;
		else if(bPt2.next == bPt2)
			return outRec1;
		else if(FirstIsBottomPt(bPt1, bPt2))
			return outRec1;
		else
			return outRec2;
	}

	boolean Param1RightOfParam2(OutRec outRec1, OutRec outRec2) {
		do {
			outRec1 = outRec1.firstLeft;
			if(outRec1 == outRec2)
				return true;
		} while(outRec1 != null);
		return false;
	}

	private OutRec GetOutRec(int idx) {
		OutRec outrec = m_PolyOuts.get(idx);
		while(outrec != m_PolyOuts.get(outrec.idx))
			outrec = m_PolyOuts.get(outrec.idx);
		return outrec;
	}

	private void AppendPolygon(TEdge e1, TEdge e2) {
		// get the start and ends of both output polygons ...
		OutRec outRec1 = m_PolyOuts.get(e1.outIdx);
		OutRec outRec2 = m_PolyOuts.get(e2.outIdx);

		OutRec holeStateRec;
		if(Param1RightOfParam2(outRec1, outRec2))
			holeStateRec = outRec2;
		else if(Param1RightOfParam2(outRec2, outRec1))
			holeStateRec = outRec1;
		else
			holeStateRec = GetLowermostRec(outRec1, outRec2);

		OutPt p1_lft = outRec1.pts;
		OutPt p1_rt = p1_lft.prev;
		OutPt p2_lft = outRec2.pts;
		OutPt p2_rt = p2_lft.prev;

		EdgeSide side;
		// join e2 poly onto e1 poly and delete pointers to e2 ...
		if(e1.side == EdgeSide.esLeft) {
			if(e2.side == EdgeSide.esLeft) {
				// z y x a b c
				ReversePolyPtLinks(p2_lft);
				p2_lft.next = p1_lft;
				p1_lft.prev = p2_lft;
				p1_rt.next = p2_rt;
				p2_rt.prev = p1_rt;
				outRec1.pts = p2_rt;
			} else {
				// x y z a b c
				p2_rt.next = p1_lft;
				p1_lft.prev = p2_rt;
				p2_lft.prev = p1_rt;
				p1_rt.next = p2_lft;
				outRec1.pts = p2_lft;
			}
			side = EdgeSide.esLeft;
		} else {
			if(e2.side == EdgeSide.esRight) {
				// a b c z y x
				ReversePolyPtLinks(p2_lft);
				p1_rt.next = p2_rt;
				p2_rt.prev = p1_rt;
				p2_lft.next = p1_lft;
				p1_lft.prev = p2_lft;
			} else {
				// a b c x y z
				p1_rt.next = p2_lft;
				p2_lft.prev = p1_rt;
				p1_lft.prev = p2_rt;
				p2_rt.next = p1_lft;
			}
			side = EdgeSide.esRight;
		}

		outRec1.bottomPt = null;
		if(holeStateRec == outRec2) {
			if(outRec2.firstLeft != outRec1)
				outRec1.firstLeft = outRec2.firstLeft;
			outRec1.isHole = outRec2.isHole;
		}
		outRec2.pts = null;
		outRec2.bottomPt = null;

		outRec2.firstLeft = outRec1;

		int OKIdx = e1.outIdx;
		int ObsoleteIdx = e2.outIdx;

		e1.outIdx = -1; // nb: safe because we only get here via AddLocalMaxPoly
		e2.outIdx = -1;

		TEdge e = m_ActiveEdges;
		while(e != null) {
			if(e.outIdx == ObsoleteIdx) {
				e.outIdx = OKIdx;
				e.side = side;
				break;
			}
			e = e.nextInAEL;
		}
		outRec2.idx = outRec1.idx;
	}

	private void ReversePolyPtLinks(OutPt pp) {
		if(pp == null)
			return;
		OutPt pp1;
		OutPt pp2;
		pp1 = pp;
		do {
			pp2 = pp1.next;
			pp1.next = pp1.prev;
			pp1.prev = pp2;
			pp1 = pp2;
		} while(pp1 != pp);
	}

	private static void SwapSides(TEdge edge1, TEdge edge2) {
		EdgeSide side = edge1.side;
		edge1.side = edge2.side;
		edge2.side = side;
	}

	private static void SwapPolyIndexes(TEdge edge1, TEdge edge2) {
		int outIdx = edge1.outIdx;
		edge1.outIdx = edge2.outIdx;
		edge2.outIdx = outIdx;
	}

	private void IntersectEdges(TEdge e1, TEdge e2, IntPoint pt, Protects protects) {
		// e1 will be to the left of e2 BELOW the intersection. Therefore e1 is before
		// e2 in AEL except when e1 is being inserted at the intersection point ...
		
		boolean e1stops = (protects == Protects.ipRight || protects == Protects.ipNone) && e1.nextInLML == null && e1.xtop == pt.x && e1.ytop == pt.y;
		boolean e2stops = (protects == Protects.ipLeft || protects == Protects.ipNone) && e2.nextInLML == null && e2.xtop == pt.x && e2.ytop == pt.y;
		
		boolean e1Contributing = (e1.outIdx >= 0);
		boolean e2contributing = (e2.outIdx >= 0);

		// update winding counts...
		// assumes that e1 will be to the right of e2 ABOVE the intersection
		if(e1.polyType == e2.polyType) {
			if(IsEvenOddFillType(e1)) {
				int oldE1WindCnt = e1.windCnt;
				e1.windCnt = e2.windCnt;
				e2.windCnt = oldE1WindCnt;
			} else {
				if(e1.windCnt + e2.windDelta == 0)
					e1.windCnt = -e1.windCnt;
				else
					e1.windCnt += e2.windDelta;
				if(e2.windCnt - e1.windDelta == 0)
					e2.windCnt = -e2.windCnt;
				else
					e2.windCnt -= e1.windDelta;
			}
		} else {
			if(!IsEvenOddFillType(e2))
				e1.windCnt2 += e2.windDelta;
			else
				e1.windCnt2 = (e1.windCnt2 == 0) ? 1 : 0;
			if(!IsEvenOddFillType(e1))
				e2.windCnt2 -= e1.windDelta;
			else
				e2.windCnt2 = (e2.windCnt2 == 0) ? 1 : 0;
		}

		PolyFillType e1FillType, e2FillType, e1FillType2, e2FillType2;
		if(e1.polyType == PolyType.ptSubject) {
			e1FillType = m_SubjFillType;
			e1FillType2 = m_ClipFillType;
		} else {
			e1FillType = m_ClipFillType;
			e1FillType2 = m_SubjFillType;
		}
		if(e2.polyType == PolyType.ptSubject) {
			e2FillType = m_SubjFillType;
			e2FillType2 = m_ClipFillType;
		} else {
			e2FillType = m_ClipFillType;
			e2FillType2 = m_SubjFillType;
		}

		int e1Wc, e2Wc;
		switch(e1FillType) {
		case POSITIVE:
			e1Wc = e1.windCnt;
			break;
		case NEGATIVE:
			e1Wc = -e1.windCnt;
			break;
		default:
			e1Wc = Math.abs(e1.windCnt);
			break;
		}
		switch(e2FillType) {
		case POSITIVE:
			e2Wc = e2.windCnt;
			break;
		case NEGATIVE:
			e2Wc = -e2.windCnt;
			break;
		default:
			e2Wc = Math.abs(e2.windCnt);
			break;
		}

		if(e1Contributing && e2contributing) {
			if(e1stops || e2stops || (e1Wc != 0 && e1Wc != 1) || (e2Wc != 0 && e2Wc != 1) || (e1.polyType != e2.polyType && m_ClipType != ClipType.XOR))
				AddLocalMaxPoly(e1, e2, pt);
			else {
				AddOutPt(e1, pt);
				AddOutPt(e2, pt);
				SwapSides(e1, e2);
				SwapPolyIndexes(e1, e2);
			}
		} else if(e1Contributing) {
			if(e2Wc == 0 || e2Wc == 1) {
				AddOutPt(e1, pt);
				SwapSides(e1, e2);
				SwapPolyIndexes(e1, e2);
			}

		} else if(e2contributing) {
			if(e1Wc == 0 || e1Wc == 1) {
				AddOutPt(e2, pt);
				SwapSides(e1, e2);
				SwapPolyIndexes(e1, e2);
			}
		} else if((e1Wc == 0 || e1Wc == 1) && (e2Wc == 0 || e2Wc == 1) && !e1stops && !e2stops) {
			// neither edge is currently contributing ...
			long e1Wc2, e2Wc2;
			switch(e1FillType2) {
			case POSITIVE:
				e1Wc2 = e1.windCnt2;
				break;
			case NEGATIVE:
				e1Wc2 = -e1.windCnt2;
				break;
			default:
				e1Wc2 = Math.abs(e1.windCnt2);
				break;
			}
			switch(e2FillType2) {
			case POSITIVE:
				e2Wc2 = e2.windCnt2;
				break;
			case NEGATIVE:
				e2Wc2 = -e2.windCnt2;
				break;
			default:
				e2Wc2 = Math.abs(e2.windCnt2);
				break;
			}

			if(e1.polyType != e2.polyType)
				AddLocalMinPoly(e1, e2, pt);
			else if(e1Wc == 1 && e2Wc == 1)
				switch(m_ClipType) {
				case INTERSECTION:
					if(e1Wc2 > 0 && e2Wc2 > 0)
						AddLocalMinPoly(e1, e2, pt);
					break;
				case UNION:
					if(e1Wc2 <= 0 && e2Wc2 <= 0)
						AddLocalMinPoly(e1, e2, pt);
					break;
				case DIFFERENCE:
					if(((e1.polyType == PolyType.ptClip) && (e1Wc2 > 0) && (e2Wc2 > 0)) || ((e1.polyType == PolyType.ptSubject) && (e1Wc2 <= 0) && (e2Wc2 <= 0)))
						AddLocalMinPoly(e1, e2, pt);
					break;
				case XOR:
					AddLocalMinPoly(e1, e2, pt);
					break;
				}
			else
				SwapSides(e1, e2);
		}

		if((e1stops != e2stops) && ((e1stops && (e1.outIdx >= 0)) || (e2stops && (e2.outIdx >= 0)))) {
			SwapSides(e1, e2);
			SwapPolyIndexes(e1, e2);
		}

		// finally, delete any non-contributing maxima edges ...
		if(e1stops)
			DeleteFromAEL(e1);
		if(e2stops)
			DeleteFromAEL(e2);
	}

	private void DeleteFromAEL(TEdge e) {
		TEdge AelPrev = e.prevInAEL;
		TEdge AelNext = e.nextInAEL;
		if(AelPrev == null && AelNext == null && (e != m_ActiveEdges))
			return; // already deleted
		if(AelPrev != null)
			AelPrev.nextInAEL = AelNext;
		else
			m_ActiveEdges = AelNext;
		if(AelNext != null)
			AelNext.prevInAEL = AelPrev;
		e.nextInAEL = null;
		e.prevInAEL = null;
	}

	private void DeleteFromSEL(TEdge e) {
		TEdge SelPrev = e.prevInSEL;
		TEdge SelNext = e.nextInSEL;
		if(SelPrev == null && SelNext == null && (e != m_SortedEdges))
			return; // already deleted
		if(SelPrev != null)
			SelPrev.nextInSEL = SelNext;
		else
			m_SortedEdges = SelNext;
		if(SelNext != null)
			SelNext.prevInSEL = SelPrev;
		e.nextInSEL = null;
		e.prevInSEL = null;
	}

	private TEdge UpdateEdgeIntoAEL(TEdge e) {
		if(e.nextInLML == null)
			throw new ClipperException("UpdateEdgeIntoAEL: invalid call");
		TEdge AelPrev = e.prevInAEL;
		TEdge AelNext = e.nextInAEL;
		e.nextInLML.outIdx = e.outIdx;
		if(AelPrev != null)
			AelPrev.nextInAEL = e.nextInLML;
		else
			m_ActiveEdges = e.nextInLML;
		if(AelNext != null)
			AelNext.prevInAEL = e.nextInLML;
		e.nextInLML.side = e.side;
		e.nextInLML.windDelta = e.windDelta;
		e.nextInLML.windCnt = e.windCnt;
		e.nextInLML.windCnt2 = e.windCnt2;
		e = e.nextInLML;
		e.prevInAEL = AelPrev;
		e.nextInAEL = AelNext;
		if(e.dx != horizontal)
			InsertScanbeam(e.ytop);
		return e;
	}

	private void ProcessHorizontals() {
		TEdge horzEdge = m_SortedEdges;
		while(horzEdge != null) {
			DeleteFromSEL(horzEdge);
			ProcessHorizontal(horzEdge);
			horzEdge = m_SortedEdges;
		}
	}

	private void ProcessHorizontal(TEdge horzEdge) {
		Direction direction;
		long horzLeft, horzRight;

		if(horzEdge.xcurr < horzEdge.xtop) {
			horzLeft = horzEdge.xcurr;
			horzRight = horzEdge.xtop;
			direction = Direction.dLeftToRight;
		} else {
			horzLeft = horzEdge.xtop;
			horzRight = horzEdge.xcurr;
			direction = Direction.dRightToLeft;
		}

		TEdge eMaxPair;
		if(horzEdge.nextInLML != null)
			eMaxPair = null;
		else
			eMaxPair = GetMaximaPair(horzEdge);

		TEdge e = GetNextInAEL(horzEdge, direction);
		while(e != null) {
			if(e.xcurr == horzEdge.xtop && eMaxPair == null) {
				if(SlopesEqual(e, horzEdge.nextInLML, m_UseFullRange)) {
					// if output polygons share an edge, they'll need joining later ...
					if(horzEdge.outIdx >= 0 && e.outIdx >= 0)
						AddJoin(horzEdge.nextInLML, e, horzEdge.outIdx, -1);
					break; // we've reached the end of the horizontal line
				} else if(e.dx < horzEdge.nextInLML.dx)
					// we really have got to the end of the intermediate horz edge so quit.
					// nb: More -ve slopes follow more +ve slopes ABOVE the horizontal.
					break;
			}

			TEdge eNext = GetNextInAEL(e, direction);
			if(eMaxPair != null || ((direction == Direction.dLeftToRight) && (e.xcurr < horzRight)) || ((direction == Direction.dRightToLeft) && (e.xcurr > horzLeft))) {
				// so far we're still in range of the horizontal edge

				if(e == eMaxPair) {
					// horzEdge is evidently a maxima horizontal and we've arrived at its end.
					if(direction == Direction.dLeftToRight) {
						IntersectEdges(horzEdge, e, new IntPoint(e.xcurr, horzEdge.ycurr), Protects.ipNone);
					} else
						IntersectEdges(e, horzEdge, new IntPoint(e.xcurr, horzEdge.ycurr), Protects.ipNone);
					if(eMaxPair.outIdx >= 0)
						throw new ClipperException("ProcessHorizontal error");
					return;
				} else if(e.dx == horizontal && !IsMinima(e) && !(e.xcurr > e.xtop)) {
					if(direction == Direction.dLeftToRight)
						IntersectEdges(horzEdge, e, new IntPoint(e.xcurr, horzEdge.ycurr), (IsTopHorz(horzEdge, e.xcurr)) ? Protects.ipLeft : Protects.ipBoth);
					else
						IntersectEdges(e, horzEdge, new IntPoint(e.xcurr, horzEdge.ycurr), (IsTopHorz(horzEdge, e.xcurr)) ? Protects.ipRight : Protects.ipBoth);
				} else if(direction == Direction.dLeftToRight) {
					IntersectEdges(horzEdge, e, new IntPoint(e.xcurr, horzEdge.ycurr), (IsTopHorz(horzEdge, e.xcurr)) ? Protects.ipLeft : Protects.ipBoth);
				} else {
					IntersectEdges(e, horzEdge, new IntPoint(e.xcurr, horzEdge.ycurr), (IsTopHorz(horzEdge, e.xcurr)) ? Protects.ipRight : Protects.ipBoth);
				}
				SwapPositionsInAEL(horzEdge, e);
			} else if((direction == Direction.dLeftToRight && e.xcurr >= horzRight) || (direction == Direction.dRightToLeft && e.xcurr <= horzLeft))
				break;
			e = eNext;
		}

		if(horzEdge.nextInLML != null) {
			if(horzEdge.outIdx >= 0)
				AddOutPt(horzEdge, new IntPoint(horzEdge.xtop, horzEdge.ytop));
			UpdateEdgeIntoAEL(horzEdge);
		} else {
			if(horzEdge.outIdx >= 0)
				IntersectEdges(horzEdge, eMaxPair, new IntPoint(horzEdge.xtop, horzEdge.ycurr), Protects.ipBoth);
			if(eMaxPair.outIdx >= 0) throw new ClipperException("ProcessHorizontal error");
			DeleteFromAEL(eMaxPair);
			DeleteFromAEL(horzEdge);
		}
	}

	private boolean IsTopHorz(TEdge horzEdge, double XPos) {
		TEdge e = m_SortedEdges;
		while(e != null) {
			if((XPos >= Math.min(e.xcurr, e.xtop)) && (XPos <= Math.max(e.xcurr, e.xtop)))
				return false;
			e = e.nextInSEL;
		}
		return true;
	}

	private TEdge GetNextInAEL(TEdge e, Direction Direction) {
		return Direction == Direction.dLeftToRight ? e.nextInAEL : e.prevInAEL;
	}

	private boolean IsMinima(TEdge e) {
		return e != null && (e.prev.nextInLML != e) && (e.next.nextInLML != e);
	}

	private boolean IsMaxima(TEdge e, double Y) {
		return (e != null && e.ytop == Y && e.nextInLML == null);
	}

	private boolean IsIntermediate(TEdge e, double Y) {
		return (e.ytop == Y && e.nextInLML != null);
	}

	private TEdge GetMaximaPair(TEdge e) {
		if(!IsMaxima(e.next, e.ytop) || (e.next.xtop != e.xtop))
			return e.prev;
		else
			return e.next;
	}

	private void ProcessIntersections(long botY, long topY) {
		if(m_ActiveEdges == null)
			return;
		BuildIntersectList(botY, topY);
		if(m_IntersectNodes == null)
			return;
		if(m_IntersectNodes.next != null) {
			FixupIntersectionOrder();
		}
		m_SortedEdges = null;
		ProcessIntersectList();
	}

	private void BuildIntersectList(long botY, long topY) {
		if(m_ActiveEdges == null)
			return;

		// prepare for sorting ...
		TEdge e = m_ActiveEdges;
		m_SortedEdges = e;
		while(e != null) {
			e.prevInSEL = e.prevInAEL;
			e.nextInSEL = e.nextInAEL;
			e.xcurr = TopX(e, topY);
			e = e.nextInAEL;
		}

		// bubblesort ...
		boolean isModified = true;
		while(isModified && m_SortedEdges != null) {
			isModified = false;
			e = m_SortedEdges;
			while(e.nextInSEL != null) {
				TEdge eNext = e.nextInSEL;
				IntPoint pt = new IntPoint();
				
				if(e.xcurr > eNext.xcurr) {
					if(!IntersectPoint(e, eNext, pt) && e.xcurr > eNext.xcurr + 1)
						throw new ClipperException("Intersection error");
					if(pt.y > botY) {
						pt.y = botY;
						pt.x = TopX(e, pt.y);
					}
					InsertIntersectNode(e, eNext, pt);
					SwapPositionsInSEL(e, eNext);
					isModified = true;
				} else
					e = eNext;
			}
			if(e.prevInSEL != null)
				e.prevInSEL.nextInSEL = null;
			else
				break;
		}
		m_SortedEdges = null;
	}

	private boolean EdgesAdjacent(IntersectNode inode) {
		return (inode.edge1.nextInSEL == inode.edge2) || (inode.edge1.prevInSEL == inode.edge2);
	}

	private void FixupIntersectionOrder() {
		// pre-condition: intersections are sorted bottom-most (then left-most) first.
		// Now it's crucial that intersections are made only between adjacent edges,
		// so to ensure this the order of intersections may need adjusting ...
		IntersectNode inode = m_IntersectNodes;
		CopyAELToSEL();
		while(inode != null) {
			if(!EdgesAdjacent(inode)) {
				IntersectNode nextNode = inode.next;
				while(nextNode != null && !EdgesAdjacent(nextNode))
					nextNode = nextNode.next;
				if(nextNode == null)
					throw new ClipperException("FixupIntersectionOrder error");
				SwapIntersectNodes(inode, nextNode);
			}
			SwapPositionsInSEL(inode.edge1, inode.edge2);
			inode = inode.next;
		}
	}

	private void ProcessIntersectList() {
		while(m_IntersectNodes != null) {
			IntersectNode iNode = m_IntersectNodes.next;
			{
				IntersectEdges(m_IntersectNodes.edge1, m_IntersectNodes.edge2, m_IntersectNodes.pt, Protects.ipBoth);
				SwapPositionsInAEL(m_IntersectNodes.edge1, m_IntersectNodes.edge2);
			}
			m_IntersectNodes = null;
			m_IntersectNodes = iNode;
		}
	}

	private static long Round(double value) {
		return value < 0 ? (long) (value - 0.5) : (long) (value + 0.5);
	}

	private static long TopX(TEdge edge, long currentY) {
		if(currentY == edge.ytop)
			return edge.xtop;
		return edge.xbot + Round(edge.dx * (currentY - edge.ybot));
	}

	private void InsertIntersectNode(TEdge e1, TEdge e2, IntPoint pt) {
		IntersectNode newNode = new IntersectNode();
		newNode.edge1 = e1;
		newNode.edge2 = e2;
		newNode.pt = pt;
		newNode.next = null;
		if(m_IntersectNodes == null)
			m_IntersectNodes = newNode;
		else if(newNode.pt.y > m_IntersectNodes.pt.y) {
			newNode.next = m_IntersectNodes;
			m_IntersectNodes = newNode;
		} else {
			IntersectNode iNode = m_IntersectNodes;
			while(iNode.next != null && newNode.pt.y < iNode.next.pt.y)
				iNode = iNode.next;
			newNode.next = iNode.next;
			iNode.next = newNode;
		}
	}

	private void SwapIntersectNodes(IntersectNode int1, IntersectNode int2) {
		TEdge e1 = int1.edge1;
		TEdge e2 = int1.edge2;
		IntPoint p = int1.pt;
		int1.edge1 = int2.edge1;
		int1.edge2 = int2.edge2;
		int1.pt = int2.pt;
		int2.edge1 = e1;
		int2.edge2 = e2;
		int2.pt = p;
	}

	private boolean IntersectPoint(TEdge edge1, TEdge edge2, final IntPoint ip) {
		double b1, b2;
		if(SlopesEqual(edge1, edge2, m_UseFullRange)) {
			if(edge2.ybot > edge1.ybot)
				ip.y = edge2.ybot;
			else
				ip.y = edge1.ybot;
			return false;
		} else if(edge1.dx == 0) {
			ip.x = edge1.xbot;
			if(edge2.dx == horizontal) {
				ip.y = edge2.ybot;
			} else {
				b2 = edge2.ybot - (edge2.xbot / edge2.dx);
				ip.y = Round(ip.x / edge2.dx + b2);
			}
		} else if(edge2.dx == 0) {
			ip.x = edge2.xbot;
			if(edge1.dx == horizontal) {
				ip.y = edge1.ybot;
			} else {
				b1 = edge1.ybot - (edge1.xbot / edge1.dx);
				ip.y = Round(ip.x / edge1.dx + b1);
			}
		} else {
			b1 = edge1.xbot - edge1.ybot * edge1.dx;
			b2 = edge2.xbot - edge2.ybot * edge2.dx;
			double q = (b2 - b1) / (edge1.dx - edge2.dx);
			ip.y = Round(q);
			if(Math.abs(edge1.dx) < Math.abs(edge2.dx))
				ip.x = Round(edge1.dx * q + b1);
			else
				ip.x = Round(edge2.dx * q + b2);
		}

		if(ip.y < edge1.ytop || ip.y < edge2.ytop) {
			if(edge1.ytop > edge2.ytop) {
				ip.x = edge1.xtop;
				ip.y = edge1.ytop;
				if(TopX(edge2, edge1.ytop) < edge1.xtop) {
					return true;
				} else {
					return false;
					
				}
			} else {
				ip.x = edge2.xtop;
				ip.y = edge2.ytop;
				if(TopX(edge1, edge2.ytop) > edge2.xtop) {
					return true;
				} else {
					return false;
				}
			}
		} else
			return true;
	}

	private void ProcessEdgesAtTopOfScanbeam(long topY) {
		TEdge e = m_ActiveEdges;
		while(e != null) {
			// 1. process maxima, treating them as if they're 'bent' horizontal edges,
			// but exclude maxima with horizontal edges. nb: e can't be a horizontal.
			if(IsMaxima(e, topY) && GetMaximaPair(e).dx != horizontal) {
				// 'e' might be removed from AEL, as may any following edges so ...
				TEdge ePrev = e.prevInAEL;
				DoMaxima(e, topY);
				if(ePrev == null)
					e = m_ActiveEdges;
				else
					e = ePrev.nextInAEL;
			} else {
				boolean intermediateVert = IsIntermediate(e, topY);
				// 2. promote horizontal edges, otherwise update xcurr and ycurr ...
				if(intermediateVert && e.nextInLML.dx == horizontal) {
					if(e.outIdx >= 0) {
						AddOutPt(e, new IntPoint(e.xtop, e.ytop));

						for(int i = 0; i < m_HorizJoins.size(); ++i) {
							final AtomicReference<IntPoint> pt = new AtomicReference<IntPoint>(new IntPoint());
							final AtomicReference<IntPoint> pt2 = new AtomicReference<IntPoint>(new IntPoint());
							HorzJoinRec hj = m_HorizJoins.get(i);

							IntPoint pt1a = new IntPoint(hj.edge.xbot, hj.edge.ybot);
							IntPoint pt1b = new IntPoint(hj.edge.xtop, hj.edge.ytop);
							IntPoint pt2a = new IntPoint(e.nextInLML.xbot, e.nextInLML.ybot);
							IntPoint pt2b = new IntPoint(e.nextInLML.xbot, e.nextInLML.ybot);

							if(GetOverlapSegment(pt1a, pt1b, pt2a, pt2b, pt, pt2)) {
								AddJoin(hj.edge, e.nextInLML, hj.savedIdx, e.outIdx);
							}
						}

						AddHorzJoin(e.nextInLML, e.outIdx);
					}
					e = UpdateEdgeIntoAEL(e);
					AddEdgeToSEL(e);
				} else {
					e.xcurr = TopX(e, topY);
					e.ycurr = topY;
					if(m_ForceSimple && e.prevInAEL != null && e.prevInAEL.xcurr == e.xcurr && e.outIdx >= 0 && e.prevInAEL.outIdx >= 0) {
						if(intermediateVert)
							AddOutPt(e.prevInAEL, new IntPoint(e.xcurr, topY));
						else
							AddOutPt(e, new IntPoint(e.xcurr, topY));
					}
				}
				e = e.nextInAEL;
			}
		}

		// 3. Process horizontals at the top of the scanbeam ...
		ProcessHorizontals();

		// 4. Promote intermediate vertices ...
		e = m_ActiveEdges;
		while(e != null) {
			if(IsIntermediate(e, topY)) {
				if(e.outIdx >= 0)
					AddOutPt(e, new IntPoint(e.xtop, e.ytop));
				e = UpdateEdgeIntoAEL(e);

				// if output polygons share an edge, they'll need joining later ...
				TEdge ePrev = e.prevInAEL;
				TEdge eNext = e.nextInAEL;
				if(ePrev != null && ePrev.xcurr == e.xbot && ePrev.ycurr == e.ybot && e.outIdx >= 0 && ePrev.outIdx >= 0 && ePrev.ycurr > ePrev.ytop && SlopesEqual(e, ePrev, m_UseFullRange)) {
					AddOutPt(ePrev, new IntPoint(e.xbot, e.ybot));
					AddJoin(e, ePrev, -1, -1);
				} else if(eNext != null && eNext.xcurr == e.xbot && eNext.ycurr == e.ybot && e.outIdx >= 0 && eNext.outIdx >= 0 && eNext.ycurr > eNext.ytop && SlopesEqual(e, eNext, m_UseFullRange)) {
					AddOutPt(eNext, new IntPoint(e.xbot, e.ybot));
					AddJoin(e, eNext, -1, -1);
				}
			}
			e = e.nextInAEL;
		}
	}

	private void DoMaxima(TEdge e, long topY) {
		TEdge eMaxPair = GetMaximaPair(e);
		long X = e.xtop;
		TEdge eNext = e.nextInAEL;
		while(eNext != eMaxPair) {
			if(eNext == null)
				throw new ClipperException("DoMaxima error");
			IntersectEdges(e, eNext, new IntPoint(X, topY), Protects.ipBoth);
			SwapPositionsInAEL(e, eNext);
			eNext = e.nextInAEL;
		}
		if(e.outIdx < 0 && eMaxPair.outIdx < 0) {
			DeleteFromAEL(e);
			DeleteFromAEL(eMaxPair);
		} else if(e.outIdx >= 0 && eMaxPair.outIdx >= 0) {
			IntersectEdges(e, eMaxPair, new IntPoint(X, topY), Protects.ipNone);
		} else
			throw new ClipperException("DoMaxima error");
	}

	public static void reversePolygons(List<Polygon> polys) {
		for(Polygon poly : polys)
			poly.reverse();
	}

	public static boolean orientation(Polygon poly) {
		return area(poly) >= 0;
	}

	private int PointCount(OutPt pts) {
		if(pts == null)
			return 0;
		int result = 0;
		OutPt p = pts;
		do {
			result++;
			p = p.next;
		} while(p != pts);
		return result;
	}

	private void BuildResult(List<Polygon> polyg) {
		polyg.clear();
		for(int i = 0; i < m_PolyOuts.size(); i++) {
			OutRec outRec = m_PolyOuts.get(i);
			if(outRec.pts == null)
				continue;
			OutPt p = outRec.pts;
			int cnt = PointCount(p);
			if(cnt < 3)
				continue;
			Polygon pg = new Polygon();
			for(int j = 0; j < cnt; j++) {
				pg.add(p.pt);
				p = p.prev;
			}
			polyg.add(pg);
		}
	}

	private void BuildResult(PolyTree polytree) {
		polytree.clear();

		// add each output polygon/contour to polytree ...
		for(int i = 0; i < m_PolyOuts.size(); i++) {
			OutRec outRec = m_PolyOuts.get(i);
			int cnt = PointCount(outRec.pts);
			if(cnt < 3)
				continue;
			FixHoleLinkage(outRec);
			PolyNode pn = new PolyNode();
			polytree.m_AllPolys.add(pn);
			outRec.polyNode = pn;
			OutPt op = outRec.pts;
			for(int j = 0; j < cnt; j++) {
				pn.m_polygon.add(op.pt);
				op = op.prev;
			}
		}

		// fixup PolyNode links etc ...
		for(int i = 0; i < m_PolyOuts.size(); i++) {
			OutRec outRec = m_PolyOuts.get(i);
			if(outRec.polyNode == null)
				continue;
			if(outRec.firstLeft == null)
				polytree.AddChild(outRec.polyNode);
			else
				outRec.firstLeft.polyNode.AddChild(outRec.polyNode);
		}
	}

	private void FixupOutPolygon(OutRec outRec) {
		// FixupOutPolygon() - removes duplicate points and simplifies consecutive
		// parallel edges by removing the middle vertex.
		OutPt lastOK = null;
		outRec.bottomPt = null;
		OutPt pp = outRec.pts;
		for(;;) {
			if(pp.prev == pp || pp.prev == pp.next) {
				outRec.pts = null;
				return;
			}
			// test for duplicate points and for same slope (cross-product) ...
			if(PointsEqual(pp.pt, pp.next.pt) || SlopesEqual(pp.prev.pt, pp.pt, pp.next.pt, m_UseFullRange)) {
				lastOK = null;
				OutPt tmp = pp;
				pp.prev.next = pp.next;
				pp.next.prev = pp.prev;
				pp = pp.prev;
				tmp = null;
			} else if(pp == lastOK)
				break;
			else {
				if(lastOK == null)
					lastOK = pp;
				pp = pp.next;
			}
		}
		outRec.pts = pp;
	}

	private boolean JoinPoints(JoinRec j, final AtomicReference<OutPt> p1, final AtomicReference<OutPt> p2) {
		p1.set(null);
		p2.set(null);
		OutRec outRec1 = m_PolyOuts.get(j.poly1Idx);
		OutRec outRec2 = m_PolyOuts.get(j.poly2Idx);
		if(outRec1 == null || outRec2 == null)
			return false;
		final AtomicReference<OutPt> pp1a = new AtomicReference<OutPt>(outRec1.pts);
		final AtomicReference<OutPt> pp2a = new AtomicReference<OutPt>(outRec2.pts);

		final AtomicReference<IntPoint> pt1 = new AtomicReference<IntPoint>(j.pt2a);
		final AtomicReference<IntPoint> pt2 = new AtomicReference<IntPoint>(j.pt2b);
		final AtomicReference<IntPoint> pt3 = new AtomicReference<IntPoint>(j.pt1a);
		final AtomicReference<IntPoint> pt4 = new AtomicReference<IntPoint>(j.pt1b);

		if(!FindSegment(pp1a, m_UseFullRange, pt1, pt2))
			return false;
		if(outRec1 == outRec2) {
			// we're searching the same polygon for overlapping segments so
			// segment 2 mustn't be the same as segment 1 ...
			pp2a.set(pp1a.get().next);
			if(!FindSegment(pp2a, m_UseFullRange, pt3, pt4) || (pp2a == pp1a))
				return false;
		} else if(!FindSegment(pp2a, m_UseFullRange, pt3, pt4))
			return false;

		if(!GetOverlapSegment(pt1.get(), pt2.get(), pt3.get(), pt4.get(), pt1, pt2))
			return false;

		OutPt p3, p4, prev = pp1a.get().prev;
		// get p1 & p2 polypts - the overlap start & endpoints on poly1
		if(PointsEqual(pp1a.get().pt, pt1.get()))
			p1.set(pp1a.get());
		else if(PointsEqual(prev.pt, pt1.get()))
			p1.set(prev);
		else
			p1.set(InsertPolyPtBetween(pp1a.get(), prev, pt1.get()));

		if(PointsEqual(pp1a.get().pt, pt2.get()))
			p2.set(pp1a.get());
		else if(PointsEqual(prev.pt, pt2.get()))
			p2.set(prev);
		else if((p1.get() == pp1a.get()) || (p1.get() == prev))
			p2.set(InsertPolyPtBetween(pp1a.get(), prev, pt2.get()));
		else if(Pt3IsBetweenPt1AndPt2(pp1a.get().pt, p1.get().pt, pt2.get()))
			p2.set(InsertPolyPtBetween(pp1a.get(), p1.get(), pt2.get()));
		else
			p2.set(InsertPolyPtBetween(p1.get(), prev, pt2.get()));

		// get p3 & p4 polypts - the overlap start & endpoints on poly2
		prev = pp2a.get().prev;
		if(PointsEqual(pp2a.get().pt, pt1.get()))
			p3 = pp2a.get();
		else if(PointsEqual(prev.pt, pt1.get()))
			p3 = prev;
		else
			p3 = InsertPolyPtBetween(pp2a.get(), prev, pt1.get());

		if(PointsEqual(pp2a.get().pt, pt2.get()))
			p4 = pp2a.get();
		else if(PointsEqual(prev.pt, pt2.get()))
			p4 = prev;
		else if((p3 == pp2a.get()) || (p3 == prev))
			p4 = InsertPolyPtBetween(pp2a.get(), prev, pt2.get());
		else if(Pt3IsBetweenPt1AndPt2(pp2a.get().pt, p3.pt, pt2.get()))
			p4 = InsertPolyPtBetween(pp2a.get(), p3, pt2.get());
		else
			p4 = InsertPolyPtBetween(p3, prev, pt2.get());

		// p1.pt == p3.pt and p2.pt == p4.pt so join p1 to p3 and p2 to p4 ...
		if(p1.get().next == p2.get() && p3.prev == p4) {
			p1.get().next = p3;
			p3.prev = p1.get();
			p2.get().prev = p4;
			p4.next = p2.get();
			return true;
		} else if(p1.get().prev == p2.get() && p3.next == p4) {
			p1.get().prev = p3;
			p3.next = p1.get();
			p2.get().next = p4;
			p4.prev = p2.get();
			return true;
		} else
			return false; // an orientation is probably wrong
	}

	private void FixupJoinRecs(JoinRec j, OutPt pt, int startIdx) {
		for(int k = startIdx; k < m_Joins.size(); k++) {
			JoinRec j2 = m_Joins.get(k);
			if(j2.poly1Idx == j.poly1Idx && PointIsVertex(j2.pt1a, pt))
				j2.poly1Idx = j.poly2Idx;
			if(j2.poly2Idx == j.poly1Idx && PointIsVertex(j2.pt2a, pt))
				j2.poly2Idx = j.poly2Idx;
		}
	}

	private boolean Poly2ContainsPoly1(OutPt outPt1, OutPt outPt2, boolean UseFulllongRange) {
		OutPt pt = outPt1;
		// Because the polygons may be touching, we need to find a vertex that
		// isn't touching the other polygon ...
		if(PointOnPolygon(pt.pt, outPt2, UseFulllongRange)) {
			pt = pt.next;
			while(pt != outPt1 && PointOnPolygon(pt.pt, outPt2, UseFulllongRange))
				pt = pt.next;
			if(pt == outPt1)
				return true;
		}
		return PointInPolygon(pt.pt, outPt2, UseFulllongRange);
	}

	private void FixupFirstLefts1(OutRec OldOutRec, OutRec NewOutRec) {
		for(int i = 0; i < m_PolyOuts.size(); i++) {
			OutRec outRec = m_PolyOuts.get(i);
			if(outRec.pts != null && outRec.firstLeft == OldOutRec) {
				if(Poly2ContainsPoly1(outRec.pts, NewOutRec.pts, m_UseFullRange))
					outRec.firstLeft = NewOutRec;
			}
		}
	}

	private void FixupFirstLefts2(OutRec OldOutRec, OutRec NewOutRec) {
		for(OutRec outRec : m_PolyOuts) {
			if(outRec.firstLeft == OldOutRec) {
				outRec.firstLeft = NewOutRec;
			}
		}
	}

	private void JoinCommonEdges() {
		for(int i = 0; i < m_Joins.size(); i++) {
			JoinRec j = m_Joins.get(i);

			OutRec outRec1 = GetOutRec(j.poly1Idx);
			OutRec outRec2 = GetOutRec(j.poly2Idx);

			if(outRec1.pts == null || outRec2.pts == null)
				continue;

			// get the polygon fragment with the correct hole state (FirstLeft)
			// before calling JoinPoints() ...
			OutRec holeStateRec;
			if(outRec1 == outRec2)
				holeStateRec = outRec1;
			else if(Param1RightOfParam2(outRec1, outRec2))
				holeStateRec = outRec2;
			else if(Param1RightOfParam2(outRec2, outRec1))
				holeStateRec = outRec1;
			else
				holeStateRec = GetLowermostRec(outRec1, outRec2);

			final AtomicReference<OutPt> p1 = new AtomicReference<OutPt>(new OutPt());
			final AtomicReference<OutPt> p2 = new AtomicReference<OutPt>(new OutPt());
			if(!JoinPoints(j, p1, p2))
				continue;

			if(outRec1 == outRec2) {
				// instead of joining two polygons, we've just created a new one by
				// splitting one polygon into two.
				outRec1.pts = p1.get();
				outRec1.bottomPt = null;
				outRec2 = CreateOutRec();
				outRec2.pts = p2.get();

				if(Poly2ContainsPoly1(outRec2.pts, outRec1.pts, m_UseFullRange)) {
					// outRec2 is contained by outRec1 ...
					outRec2.isHole = !outRec1.isHole;
					outRec2.firstLeft = outRec1;

					FixupJoinRecs(j, p2.get(), i + 1);

					// fixup FirstLeft pointers that may need reassigning to OutRec1
					if(m_UsingPolyTree)
						FixupFirstLefts2(outRec2, outRec1);

					FixupOutPolygon(outRec1); // nb: do this BEFORE testing orientation
					FixupOutPolygon(outRec2); // but AFTER calling FixupJoinRecs()

					if((outRec2.isHole ^ m_ReverseOutput) == (Area(outRec2, m_UseFullRange) > 0))
						ReversePolyPtLinks(outRec2.pts);

				} else if(Poly2ContainsPoly1(outRec1.pts, outRec2.pts, m_UseFullRange)) {
					// outRec1 is contained by outRec2 ...
					outRec2.isHole = outRec1.isHole;
					outRec1.isHole = !outRec2.isHole;
					outRec2.firstLeft = outRec1.firstLeft;
					outRec1.firstLeft = outRec2;

					FixupJoinRecs(j, p2.get(), i + 1);

					// fixup FirstLeft pointers that may need reassigning to OutRec1
					if(m_UsingPolyTree)
						FixupFirstLefts2(outRec1, outRec2);

					FixupOutPolygon(outRec1); // nb: do this BEFORE testing orientation
					FixupOutPolygon(outRec2); // but AFTER calling FixupJoinRecs()

					if((outRec1.isHole ^ m_ReverseOutput) == (Area(outRec1, m_UseFullRange) > 0))
						ReversePolyPtLinks(outRec1.pts);
				} else {
					// the 2 polygons are completely separate ...
					outRec2.isHole = outRec1.isHole;
					outRec2.firstLeft = outRec1.firstLeft;

					FixupJoinRecs(j, p2.get(), i + 1);

					// fixup FirstLeft pointers that may need reassigning to OutRec2
					if(m_UsingPolyTree)
						FixupFirstLefts1(outRec1, outRec2);

					FixupOutPolygon(outRec1); // nb: do this BEFORE testing orientation
					FixupOutPolygon(outRec2); // but AFTER calling FixupJoinRecs()
				}
			} else {
				// joined 2 polygons together ...

				// cleanup redundant edges ...
				FixupOutPolygon(outRec1);

				outRec2.pts = null;
				outRec2.bottomPt = null;
				outRec2.idx = outRec1.idx;

				outRec1.isHole = holeStateRec.isHole;
				if(holeStateRec == outRec2)
					outRec1.firstLeft = outRec2.firstLeft;
				outRec2.firstLeft = outRec1;

				// fixup FirstLeft pointers that may need reassigning to OutRec1
				if(m_UsingPolyTree)
					FixupFirstLefts2(outRec2, outRec1);
			}
		}
	}

	private void UpdateOutPtIdxs(OutRec outrec) {
		OutPt op = outrec.pts;
		do {
			op.idx = outrec.idx;
			op = op.prev;
		} while(op != outrec.pts);
	}

	private void DoSimplePolygons() {
		int i = 0;
		while(i < m_PolyOuts.size()) {
			OutRec outrec = m_PolyOuts.get(i++);
			OutPt op = outrec.pts;
			if(op == null)
				continue;
			do // for each Pt in Polygon until duplicate found do ...
			{
				OutPt op2 = op.next;
				while(op2 != outrec.pts) {
					if(PointsEqual(op.pt, op2.pt) && op2.next != op && op2.prev != op) {
						// split the polygon into two ...
						OutPt op3 = op.prev;
						OutPt op4 = op2.prev;
						op.prev = op4;
						op4.next = op;
						op2.prev = op3;
						op3.next = op2;

						outrec.pts = op;
						OutRec outrec2 = CreateOutRec();
						outrec2.pts = op2;
						UpdateOutPtIdxs(outrec2);
						if(Poly2ContainsPoly1(outrec2.pts, outrec.pts, m_UseFullRange)) {
							// OutRec2 is contained by OutRec1 ...
							outrec2.isHole = !outrec.isHole;
							outrec2.firstLeft = outrec;
						} else if(Poly2ContainsPoly1(outrec.pts, outrec2.pts, m_UseFullRange)) {
							// OutRec1 is contained by OutRec2 ...
							outrec2.isHole = outrec.isHole;
							outrec.isHole = !outrec2.isHole;
							outrec2.firstLeft = outrec.firstLeft;
							outrec.firstLeft = outrec2;
						} else {
							// the 2 polygons are separate ...
							outrec2.isHole = outrec.isHole;
							outrec2.firstLeft = outrec.firstLeft;
						}
						op2 = op; // ie get ready for the next iteration
					}
					op2 = op2.next;
				}
				op = op.next;
			} while(op != outrec.pts);
		}
	}

	private static boolean FullRangeNeeded(Polygon pts) {
		boolean result = false;
		for(int i = 0; i < pts.size(); i++) {
			if(Math.abs(pts.get(i).x) > hiRange || Math.abs(pts.get(i).y) > hiRange)
				throw new ClipperException("Coordinate exceeds range bounds.");
			else if(Math.abs(pts.get(i).x) > loRange || Math.abs(pts.get(i).y) > loRange)
				result = true;
		}
		return result;
	}

	public static double area(Polygon poly) {
		int highI = poly.size() - 1;
		if(highI < 2)
			return 0;
		if(FullRangeNeeded(poly)) {
			Int128 a = new Int128(0);
			a = Int128.multiply(poly.get(highI).x + poly.get(0).x, poly.get(0).y - poly.get(highI).y);
			for(int i = 1; i <= highI; ++i)
				a = a.add(Int128.multiply(poly.get(i - 1).x + poly.get(i).x, poly.get(i).y - poly.get(i - 1).y));
			return a.toDouble() / 2;
		} else {
			double area = ((double) poly.get(highI).x + poly.get(0).x) * ((double) poly.get(0).y - poly.get(highI).y);
			for(int i = 1; i <= highI; ++i)
				area += ((double) poly.get(i - 1).x + poly.get(i).x) * ((double) poly.get(i).y - poly.get(i - 1).y);
			return area / 2;
		}
	}
	
	double Area(OutRec outRec, boolean UseFull64BitRange) {
		OutPt op = outRec.pts;
		if(op == null)
			return 0;
		if(UseFull64BitRange) {
			Int128 a = new Int128(0);
			do {
				a = a.add(Int128.multiply(op.pt.x + op.prev.pt.x, op.prev.pt.y - op.pt.y));
				op = op.next;
			} while(op != outRec.pts);
			return a.toDouble() / 2;
		} else {
			double a = 0;
			do {
				a = a + (op.pt.x + op.prev.pt.x) * (op.prev.pt.y - op.pt.y);
				op = op.next;
			} while(op != outRec.pts);
			return a / 2;
		}
	}

	// OffsetPolygon functions ...

	static Polygon BuildArc(IntPoint pt, double a1, double a2, double r, double limit) {
		// see notes in clipper.pas regarding steps
		double arcFrac = Math.abs(a2 - a1) / (2 * Math.PI);
		int steps = (int) (arcFrac * Math.PI / Math.acos(1 - limit / Math.abs(r)));
		if(steps < 2)
			steps = 2;
		else if(steps > (int) (222.0 * arcFrac))
			steps = (int) (222.0 * arcFrac);

		double x = Math.cos(a1);
		double y = Math.sin(a1);
		double c = Math.cos((a2 - a1) / steps);
		double s = Math.sin((a2 - a1) / steps);
		Polygon result = new Polygon();
		for(int i = 0; i <= steps; ++i) {
			result.add(new IntPoint(pt.x + Round(x * r), pt.y + Round(y * r)));
			double x2 = x;
			x = x * c - s * y; // cross product
			y = x2 * s + y * c; // dot product
		}
		return result;
	}

	static DoublePoint GetUnitNormal(IntPoint pt1, IntPoint pt2) {
		double dx = (pt2.x - pt1.x);
		double dy = (pt2.y - pt1.y);
		if((dx == 0) && (dy == 0))
			return new DoublePoint();

		double f = 1 * 1.0 / Math.sqrt(dx * dx + dy * dy);
		dx *= f;
		dy *= f;

		return new DoublePoint(dy, -dx);
	}

	static class DoublePoint {
		public double X;
		public double Y;

		public DoublePoint() {
		}

		public DoublePoint(double x, double y) {
			this.X = x;
			this.Y = y;
		}

		public DoublePoint(DoublePoint dp) {
			this.X = dp.X;
			this.Y = dp.Y;
		}
	};

	private static class PolyOffsetBuilder {
		private List<Polygon> m_p;
		private Polygon currentPoly;
		private List<DoublePoint> normals = new ArrayList<DoublePoint>();
		private double m_delta, m_r, m_rmin;
		private int m_i, m_j, m_k;
		private final int m_buffLength = 128;

		void OffsetPoint(JoinType jointype, double limit) {
			switch(jointype) {
			case jtMiter: {
				m_r = 1 + (normals.get(m_j).X * normals.get(m_k).X + normals.get(m_j).Y * normals.get(m_k).Y);
				if(m_r >= m_rmin)
					DoMiter();
				else
					DoSquare();
				break;
			}
			case jtSquare:
				DoSquare();
				break;
			case jtRound:
				DoRound(limit);
				break;
			}
			m_k = m_j;
		}

		public PolyOffsetBuilder(List<Polygon> pts, List<Polygon> solution, boolean isPolygon, double delta, JoinType jointype, EndType endtype) {
			this(pts, solution, isPolygon, delta, jointype, endtype, 0);
		}

		public PolyOffsetBuilder(List<Polygon> pts, List<Polygon> solution, boolean isPolygon, double delta, JoinType jointype, EndType endtype, double limit) {
			// precondition: solution != pts

			if(delta == 0) {
				solution.clear();
				solution.addAll(pts);
				return;
			}
			m_p = pts;
			m_delta = delta;
			m_rmin = 0.5;

			if(jointype == JoinType.jtMiter) {
				if(limit > 2)
					m_rmin = 2.0 / (limit * limit);
				limit = 0.25; // just in case endtype == etRound
			} else {
				if(limit <= 0)
					limit = 0.25;
				else if(limit > Math.abs(delta))
					limit = Math.abs(delta);
			}

			double deltaSq = delta * delta;
			solution.clear();
			for(m_i = 0; m_i < pts.size(); m_i++) {
				int len = pts.get(m_i).size();
				if(len == 0 || (len < 3 && delta <= 0))
					continue;
				else if(len == 1) {
					currentPoly = BuildArc(pts.get(m_i).get(0), 0, 2 * Math.PI, delta, limit);
					solution.add(currentPoly);
					continue;
				}

				boolean forceClose = PointsEqual(pts.get(m_i).get(0), pts.get(m_i).get(len - 1));
				if(forceClose)
					len--;

				// build normals ...
				normals.clear();
				for(int j = 0; j < len - 1; ++j)
					normals.add(GetUnitNormal(pts.get(m_i).get(j), pts.get(m_i).get(j + 1)));
				if(isPolygon || forceClose)
					normals.add(GetUnitNormal(pts.get(m_i).get(len - 1), pts.get(m_i).get(0)));
				else
					normals.add(new DoublePoint(normals.get(len - 2)));

				currentPoly = new Polygon();
				if(isPolygon || forceClose) {
					m_k = len - 1;
					for(m_j = 0; m_j < len; ++m_j)
						OffsetPoint(jointype, limit);
					solution.add(currentPoly);
					if(!isPolygon) {
						currentPoly = new Polygon();
						m_delta = -m_delta;
						m_k = len - 1;
						for(m_j = 0; m_j < len; ++m_j)
							OffsetPoint(jointype, limit);
						m_delta = -m_delta;
						currentPoly.reverse();
						solution.add(currentPoly);
					}
				} else {
					m_k = 0;
					for(m_j = 1; m_j < len - 1; ++m_j)
						OffsetPoint(jointype, limit);

					IntPoint pt1;
					if(endtype == EndType.BUTT) {
						m_j = len - 1;
						pt1 = new IntPoint((long) Round(pts.get(m_i).get(m_j).x + normals.get(m_j).X * delta), (long) Round(pts.get(m_i).get(m_j).y + normals.get(m_j).Y * delta));
						AddPoint(pt1);
						pt1 = new IntPoint((long) Round(pts.get(m_i).get(m_j).x - normals.get(m_j).X * delta), (long) Round(pts.get(m_i).get(m_j).y - normals.get(m_j).Y * delta));
						AddPoint(pt1);
					} else {
						m_j = len - 1;
						m_k = len - 2;
						normals.get(m_j).X = -normals.get(m_j).X;
						normals.get(m_j).Y = -normals.get(m_j).Y;
						if(endtype == EndType.SQUARE)
							DoSquare();
						else
							DoRound(limit);
					}

					// re-build Normals ...
					for(int j = len - 1; j > 0; j--) {
						normals.get(j).X = -normals.get(j - 1).X;
						normals.get(j).Y = -normals.get(j - 1).Y;
					}
					normals.get(0).X = -normals.get(1).X;
					normals.get(0).Y = -normals.get(1).Y;

					m_k = len - 1;
					for(m_j = m_k - 1; m_j > 0; --m_j)
						OffsetPoint(jointype, limit);

					if(endtype == EndType.BUTT) {
						pt1 = new IntPoint((long) Round(pts.get(m_i).get(0).x - normals.get(0).X * delta), (long) Round(pts.get(m_i).get(0).y - normals.get(0).Y * delta));
						AddPoint(pt1);
						pt1 = new IntPoint((long) Round(pts.get(m_i).get(0).x + normals.get(0).X * delta), (long) Round(pts.get(m_i).get(0).y + normals.get(0).Y * delta));
						AddPoint(pt1);
					} else {
						m_k = 1;
						if(endtype == EndType.SQUARE)
							DoSquare();
						else
							DoRound(limit);
					}
					solution.add(currentPoly);
				}
			}

			// finally, clean up untidy corners ...
			Clipper clpr = new Clipper();
			clpr.addPolygons(solution, PolyType.ptSubject);
			if(delta > 0) {
				clpr.execute(ClipType.UNION, solution, PolyFillType.POSITIVE, PolyFillType.POSITIVE);
			} else {
				IntRect r = clpr.getBounds();
				Polygon outer = new Polygon();

				outer.add(new IntPoint(r.left - 10, r.bottom + 10));
				outer.add(new IntPoint(r.right + 10, r.bottom + 10));
				outer.add(new IntPoint(r.right + 10, r.top - 10));
				outer.add(new IntPoint(r.left - 10, r.top - 10));

				clpr.addPolygon(outer, PolyType.ptSubject);
				clpr.setReverseSolution(true);
				clpr.execute(ClipType.UNION, solution, PolyFillType.NEGATIVE, PolyFillType.NEGATIVE);
				if(solution.size() > 0)
					solution.remove(0);
			}
		}

		void AddPoint(IntPoint pt) {
			currentPoly.add(pt);
		}

		void DoSquare() {
			IntPoint pt1 = new IntPoint((long) Round(m_p.get(m_i).get(m_j).x + normals.get(m_k).X * m_delta), (long) Round(m_p.get(m_i).get(m_j).y + normals.get(m_k).Y * m_delta));
			IntPoint pt2 = new IntPoint((long) Round(m_p.get(m_i).get(m_j).x + normals.get(m_j).X * m_delta), (long) Round(m_p.get(m_i).get(m_j).y + normals.get(m_j).Y * m_delta));
			if((normals.get(m_k).X * normals.get(m_j).Y - normals.get(m_j).X * normals.get(m_k).Y) * m_delta >= 0) {
				double a1 = Math.atan2(normals.get(m_k).Y, normals.get(m_k).X);
				double a2 = Math.atan2(-normals.get(m_j).Y, -normals.get(m_j).X);
				a1 = Math.abs(a2 - a1);
				if(a1 > Math.PI)
					a1 = Math.PI * 2 - a1;
				double dx = Math.tan((Math.PI - a1) / 4) * Math.abs(m_delta);
				pt1 = new IntPoint((long) (pt1.x - normals.get(m_k).Y * dx), (long) (pt1.y + normals.get(m_k).X * dx));
				AddPoint(pt1);
				pt2 = new IntPoint((long) (pt2.x + normals.get(m_j).Y * dx), (long) (pt2.y - normals.get(m_j).X * dx));
				AddPoint(pt2);
			} else {
				AddPoint(pt1);
				AddPoint(m_p.get(m_i).get(m_j));
				AddPoint(pt2);
			}
		}

		void DoMiter() {
			if((normals.get(m_k).X * normals.get(m_j).Y - normals.get(m_j).X * normals.get(m_k).Y) * m_delta >= 0) {
				double q = m_delta / m_r;
				AddPoint(new IntPoint((long) Round(m_p.get(m_i).get(m_j).x + (normals.get(m_k).X + normals.get(m_j).X) * q), (long) Round(m_p.get(m_i).get(m_j).y + (normals.get(m_k).Y + normals.get(m_j).Y) * q)));
			} else {
				IntPoint pt1 = new IntPoint((long) Round(m_p.get(m_i).get(m_j).x + normals.get(m_k).X * m_delta), (long) Round(m_p.get(m_i).get(m_j).y + normals.get(m_k).Y * m_delta));
				IntPoint pt2 = new IntPoint((long) Round(m_p.get(m_i).get(m_j).x + normals.get(m_j).X * m_delta), (long) Round(m_p.get(m_i).get(m_j).y + normals.get(m_j).Y * m_delta));
				AddPoint(pt1);
				AddPoint(m_p.get(m_i).get(m_j));
				AddPoint(pt2);
			}
		}

		void DoRound(double Limit) {
			IntPoint pt1 = new IntPoint(Round(m_p.get(m_i).get(m_j).x + normals.get(m_k).X * m_delta), Round(m_p.get(m_i).get(m_j).y + normals.get(m_k).Y * m_delta));
			IntPoint pt2 = new IntPoint(Round(m_p.get(m_i).get(m_j).x + normals.get(m_j).X * m_delta), Round(m_p.get(m_i).get(m_j).y + normals.get(m_j).Y * m_delta));
			AddPoint(pt1);
			// round off reflex angles (ie > 180 deg) unless almost flat (ie < 10deg).
			// cross product normals < 0 . angle > 180 deg.
			// dot product normals == 1 . no angle
			if((normals.get(m_k).X * normals.get(m_j).Y - normals.get(m_j).X * normals.get(m_k).Y) * m_delta >= 0) {
				if((normals.get(m_j).X * normals.get(m_k).X + normals.get(m_j).Y * normals.get(m_k).Y) < 0.985) {
					double a1 = Math.atan2(normals.get(m_k).Y, normals.get(m_k).X);
					double a2 = Math.atan2(normals.get(m_j).Y, normals.get(m_j).X);
					if(m_delta > 0 && a2 < a1)
						a2 += Math.PI * 2;
					else if(m_delta < 0 && a2 > a1)
						a2 -= Math.PI * 2;
					Polygon arc = BuildArc(m_p.get(m_i).get(m_j), a1, a2, m_delta, Limit);
					for(int m = 0; m < arc.size(); m++)
						AddPoint(arc.get(m));
				}
			} else
				AddPoint(m_p.get(m_i).get(m_j));
			AddPoint(pt2);
		}
	}

	static boolean updateBotPt(IntPoint pt, IntPoint botPt) {
		return pt.y > botPt.y || (pt.y == botPt.y && pt.x < botPt.x);
	}

	public static List<Polygon> offsetPolygons(List<Polygon> poly, double delta, JoinType joinType, double miterLimit, boolean autoFix) {
		List<Polygon> result = new ArrayList<Polygon>();

		// AutoFix - fixes polygon orientation if necessary and removes
		// duplicate vertices. Can be set false when you're sure that polygon
		// orientation is correct and that there are no duplicate vertices.
		if(autoFix) {
			int Len = poly.size(), botI = 0;
			while(botI < Len && poly.get(botI).size() == 0)
				botI++;
			if(botI == Len)
				return result;

			// botPt: used to find the lowermost (in inverted Y-axis) & leftmost point
			// This point (on pts[botI]) must be on an outer polygon ring and if
			// its orientation is false (counterclockwise) then assume all polygons
			// need reversing ...
			IntPoint botPt = poly.get(botI).get(0);
			for(int i = botI; i < Len; ++i) {
				if(poly.get(i).size() == 0) {
					continue;
				}
				if(updateBotPt(poly.get(i).get(0), botPt)) {
					botPt = poly.get(i).get(0);
					botI = i;
				}
				for(int j = poly.get(i).size() - 1; j > 0; j--) {
					if(PointsEqual(poly.get(i).get(j), poly.get(i).get(j - 1))) {
						poly.get(i).remove(j);
					} else if(updateBotPt(poly.get(i).get(j), botPt)) {
						botPt = poly.get(i).get(j);
						botI = i;
					}
				}
			}
			if(!orientation(poly.get(botI)))
				reversePolygons(poly);
		}

		new PolyOffsetBuilder(poly, result, true, delta, joinType, EndType.CLOSED, miterLimit);
		return result;
	}

	public static List<Polygon> offsetPolygons(List<Polygon> poly, double delta, JoinType jointype, double miterLimit) {
		return offsetPolygons(poly, delta, jointype, miterLimit, true);
	}

	public static List<Polygon> offsetPolygons(List<Polygon> poly, double delta, JoinType jointype) {
		return offsetPolygons(poly, delta, jointype, 0, true);
	}

	public static List<Polygon> offsetPolygons(List<Polygon> poly, double delta) {
		return offsetPolygons(poly, delta, JoinType.jtSquare, 0, true);
	}

	public static List<Polygon> offsetPolyLines(List<Polygon> lines, double delta, JoinType jointype, EndType endType, double limit) {
		List<Polygon> result = new ArrayList<Polygon>();

		// automatically strip duplicate points because it gets complicated with
		// open and closed lines and when to strip duplicates across begin-end ...
		List<Polygon> pts = new ArrayList<Polygon>();
		for(int i = 0; i < pts.size(); ++i) {
			for(int j = pts.get(i).size() - 1; j > 0; j--)
				if(PointsEqual(pts.get(i).get(j), pts.get(i).get(j - 1)))
					pts.get(i).remove(j);
		}

		if(endType == EndType.CLOSED) {
			int sz = pts.size();
			for(int i = 0; i < sz; ++i) {
				Polygon line = new Polygon(pts.get(i));
				line.reverse();
				pts.add(line);
			}
			new PolyOffsetBuilder(pts, result, true, delta, jointype, endType, limit);
		} else
			new PolyOffsetBuilder(pts, result, false, delta, jointype, endType, limit);

		return result;
	}

	// SimplifyPolygon functions ...
	// Convert self-intersecting polygons into simple polygons

	public static List<Polygon> simplifyPolygon(Polygon poly) {
		return simplifyPolygon(poly, PolyFillType.EVENODD);
	}

	public static List<Polygon> simplifyPolygon(Polygon poly, PolyFillType fillType) {
		List<Polygon> result = new ArrayList<Polygon>();
		Clipper c = new Clipper();
		c.setForceSimple(true);
		c.addPolygon(poly, PolyType.ptSubject);
		c.execute(ClipType.UNION, result, fillType, fillType);
		return result;
	}

	public static List<Polygon> simplifyPolygons(List<Polygon> polys) {
		return simplifyPolygons(polys, PolyFillType.EVENODD);
	}

	public static List<Polygon> simplifyPolygons(List<Polygon> polys, PolyFillType fillType) {
		List<Polygon> result = new ArrayList<Polygon>();
		Clipper c = new Clipper();
		c.setForceSimple(true);
		c.addPolygons(polys, PolyType.ptSubject);
		c.execute(ClipType.UNION, result, fillType, fillType);
		return result;
	}

	private static double DistanceSqrd(IntPoint pt1, IntPoint pt2) {
		double dx = ((double) pt1.x - pt2.x);
		double dy = ((double) pt1.y - pt2.y);
		return (dx * dx + dy * dy);
	}

	private static DoublePoint ClosestPointOnLine(IntPoint pt, IntPoint linePt1, IntPoint linePt2) {
		double dx = ((double) linePt2.x - linePt1.x);
		double dy = ((double) linePt2.y - linePt1.y);
		if(dx == 0 && dy == 0)
			return new DoublePoint(linePt1.x, linePt1.y);
		double q = ((pt.x - linePt1.x) * dx + (pt.y - linePt1.y) * dy) / (dx * dx + dy * dy);
		return new DoublePoint((1 - q) * linePt1.x + q * linePt2.x, (1 - q) * linePt1.y + q * linePt2.y);
	}

	private static boolean SlopesNearColinear(IntPoint pt1, IntPoint pt2, IntPoint pt3, double distSqrd) {
		if(DistanceSqrd(pt1, pt2) > DistanceSqrd(pt1, pt3))
			return false;
		DoublePoint cpol = ClosestPointOnLine(pt2, pt1, pt3);
		double dx = pt2.x - cpol.X;
		double dy = pt2.y - cpol.Y;
		return (dx * dx + dy * dy) < distSqrd;
	}

	private static boolean PointsAreClose(IntPoint pt1, IntPoint pt2, double distSqrd) {
		double dx = (double) pt1.x - pt2.x;
		double dy = (double) pt1.y - pt2.y;
		return ((dx * dx) + (dy * dy) <= distSqrd);
	}

	public static Polygon cleanPolygon(Polygon poly) {
		return cleanPolygon(poly, 1.414);
	}

	public static Polygon cleanPolygon(Polygon poly, double distance) {
		// distance = proximity in units/pixels below which vertices
		// will be stripped. Default ~= sqrt(2) so when adjacent
		// vertices have both x & y coords within 1 unit, then
		// the second vertex will be stripped.
		double distSqrd = (distance * distance);
		int highI = poly.size() - 1;
		Polygon result = new Polygon();
		while(highI > 0 && PointsAreClose(poly.get(highI), poly.get(0), distSqrd))
			highI--;
		if(highI < 2)
			return result;
		IntPoint pt = poly.get(highI);
		int i = 0;
		for(;;) {
			while(i < highI && PointsAreClose(pt, poly.get(i), distSqrd))
				i += 2;
			int i2 = i;
			while(i < highI && (PointsAreClose(poly.get(i), poly.get(i + 1), distSqrd) || SlopesNearColinear(pt, poly.get(i), poly.get(i + 1), distSqrd)))
				i++;
			if(i >= highI)
				break;
			else if(i != i2)
				continue;
			pt = poly.get(i++);
			result.add(pt);
		}
		if(i <= highI)
			result.add(poly.get(i));
		i = result.size();
		if(i > 2 && SlopesNearColinear(result.get(i - 2), result.get(i - 1), result.get(0), distSqrd))
			result.remove(i - 1);
		if(result.size() < 3)
			result.clear();
		return result;
	}

	public static List<Polygon> cleanPolygons(List<Polygon> polys) {
		return cleanPolygons(polys, 1.415);
	}

	public static List<Polygon> cleanPolygons(List<Polygon> polys, double distance) {
		List<Polygon> result = new ArrayList<Polygon>();
		for(int i = 0; i < polys.size(); i++)
			result.add(cleanPolygon(polys.get(i), distance));
		return result;
	}

	public static void polyTreeToPolygons(PolyTree polytree, List<Polygon> polygons) {
		polygons.clear();
		addPolyNodeToPolygons(polytree, polygons);
	}

	public static void addPolyNodeToPolygons(PolyNode polynode, List<Polygon> polygons) {
		if(polynode.getContour().size() > 0)
			polygons.add(polynode.getContour());
		for(PolyNode pn : polynode.getChilds()) {
			addPolyNodeToPolygons(pn, polygons);
		}
	}
}