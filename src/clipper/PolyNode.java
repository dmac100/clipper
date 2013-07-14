package clipper;

import java.util.ArrayList;
import java.util.List;

public class PolyNode {
	PolyNode m_Parent;
	Polygon m_polygon = new Polygon();
	int m_Index;
	List<PolyNode> m_Childs = new ArrayList<PolyNode>();

	private boolean isHoleNode() {
		boolean result = true;
		PolyNode node = m_Parent;
		while(node != null) {
			result = !result;
			node = node.m_Parent;
		}
		return result;
	}

	public int getChildCount() {
		return m_Childs.size();
	}

	public Polygon getContour() {
		return m_polygon;
	}

	void AddChild(PolyNode Child) {
		int cnt = m_Childs.size();
		m_Childs.add(Child);
		Child.m_Parent = this;
		Child.m_Index = cnt;
	}

	public PolyNode getNext() {
		if(m_Childs.size() > 0)
			return m_Childs.get(0);
		else
			return GetNextSiblingUp();
	}

	PolyNode GetNextSiblingUp() {
		if(m_Parent == null)
			return null;
		else if(m_Index == m_Parent.m_Childs.size() - 1)
			return m_Parent.GetNextSiblingUp();
		else
			return m_Parent.m_Childs.get(m_Index + 1);
	}

	public List<PolyNode> getChilds() {
		return m_Childs;
	}

	public PolyNode getParent() {
		return m_Parent;
	}

	public boolean isHole() {
		return isHoleNode();
	}
}