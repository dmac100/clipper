package clipper.ui;

import java.awt.BorderLayout;
import java.awt.Button;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.RenderingHints;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.BasicStroke;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.geom.GeneralPath;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;

import javax.jws.soap.SOAPBinding.Style;
import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.border.TitledBorder;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;

import clipper.Polygon;

class PolygonCanvas extends JPanel {
	private List<Polygon> subjectPolygons = new ArrayList<Polygon>();
	private List<Polygon> clipPolygons = new ArrayList<Polygon>();
	private List<Polygon> solutionPolygons = new ArrayList<Polygon>();
	private long scale = 1;
	
	private double subjectArea;
	private double clipArea;
	private double intersectionArea;
	private double unionArea;
	private double calculatedUnion;
	
	public void paintComponent(Graphics g) {
		paintComponent((Graphics2D)g);
	}
	
	public void paintComponent(Graphics2D g) {
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

		g.setBackground(new Color(255, 255, 255));
		g.clearRect(0, 0, getWidth(), getHeight());

		drawPolygons(g, subjectPolygons, new Color(218, 218, 228, 150), new Color(210, 214, 234, 200));
		drawPolygons(g, clipPolygons, new Color(255, 230, 230, 150), new Color(255, 180, 180, 200));
		drawPolygons(g, solutionPolygons, new Color(180, 250, 171, 200), new Color(0, 0, 0, 0));
		
		drawAreas(g);

		drawShadow(g, solutionPolygons);
	}
	
	private void drawPolygons(Graphics2D g, List<Polygon> polygons, Color fill, Color stroke) {
		GeneralPath path = createPath(polygons, 0, 0);
	
		g.setColor(fill);
		g.fill(path);

		g.setColor(stroke);
		g.draw(path);
	}

	private void drawShadow(Graphics2D g, List<Polygon> polygons) {
		GeneralPath path = createPath(polygons, 0, 0);
		g.clip(path);
		g.setStroke(new BasicStroke(2));

		for(int x = 0; x < 3; x++) {
			g.setColor(new Color(30, 80, 30, 100 - x*50));
			g.draw(createPath(polygons, -x, -x));

			g.setColor(new Color(220, 255, 220, 250 - x*100));
			g.draw(createPath(polygons, x, x));
		}

		g.setStroke(new BasicStroke(1));
		g.setClip(null);
		g.setColor(new Color(70, 120, 70, 200));
		g.draw(path);
	}

	private GeneralPath createPath(List<Polygon> polygons, int offsetX, int offsetY) {
		GeneralPath path = new GeneralPath();
		
		for(Polygon polygon:polygons) {
			path.moveTo(polygon.get(0).x / scale, polygon.get(0).y / scale);
			for(int i = 1; i < polygon.size(); i++) {
				path.lineTo(polygon.get(i).x / scale + offsetX, polygon.get(i).y / scale + offsetY);
			}
			path.closePath();
		}

		return path;
	}
	
	private void drawAreas(Graphics2D g) {
		g.setFont(new Font("Arial", Font.PLAIN, 10));
		
		FontMetrics metrics = g.getFontMetrics(g.getFont());
		
		int maxWidth = 0;
		maxWidth = Math.max(maxWidth, (int)metrics.getStringBounds(formatArea(subjectArea), g).getWidth());
		maxWidth = Math.max(maxWidth, (int)metrics.getStringBounds(formatArea(clipArea), g).getWidth());
		maxWidth = Math.max(maxWidth, (int)metrics.getStringBounds(formatArea(intersectionArea), g).getWidth());
		maxWidth = Math.max(maxWidth, (int)metrics.getStringBounds(formatArea(calculatedUnion), g).getWidth());
		maxWidth = Math.max(maxWidth, (int)metrics.getStringBounds(formatArea(unionArea), g).getWidth());
		
		int offset = (int)metrics.getStringBounds("intersect: ", g).getWidth();
		int lineWidth = offset + maxWidth;
		int lineHeight = metrics.getHeight();

		g.setColor(new Color(240, 240, 240, 170));
		g.fillRect(4, 5, lineWidth + 5, lineHeight * 7 + 5);
		g.setColor(new Color(0, 0, 0, 150));
		g.drawRect(4, 5, lineWidth + 5, lineHeight * 7 + 5);
		
		g.translate(7, lineHeight + 5);

		g.setColor(new Color(50, 50, 50, 200));
		g.drawString("subject:   ", 0, lineHeight*0);
		g.drawString("clip:      ", 0, lineHeight*1);
		g.drawString("intersect: ", 0, lineHeight*2);
		g.drawString("-------------------", 0,  lineHeight*3);
		g.drawString("s + c - i: ", 0, lineHeight*4);
		g.drawString("-------------------", 0, lineHeight*5);
		g.drawString("union: ", 0, lineHeight*6);
		
		g.drawString(formatArea(subjectArea), offset, lineHeight*0);
		g.drawString(formatArea(clipArea), offset, lineHeight*1);
		g.drawString(formatArea(intersectionArea), offset, lineHeight*2);
		g.drawString(formatArea(calculatedUnion), offset, lineHeight*4);
		g.drawString(formatArea(unionArea), offset, lineHeight*6);

		g.translate(-7, -lineHeight - 5);
	}
	
	private String formatArea(double area) {
		DecimalFormat format = new DecimalFormat("#,###");
		return format.format(area / (100 * scale));
	}
	
	public void setSubjectPolygons(List<Polygon> polygons) {
		this.subjectPolygons = polygons;
		repaint();
	}

	public void setClipPolygons(List<Polygon> polygons) {
		this.clipPolygons = polygons;
		repaint();
	}

	public void setSolutionPolygons(List<Polygon> polygons) {
		this.solutionPolygons = polygons;
		repaint();
	}

	public void setScale(long scale) {
		this.scale = scale;
	}

	public void setAreas(double subjectArea, double clipArea, double intersectionArea, double unionArea, double calculatedUnion) {
		this.subjectArea = subjectArea;
		this.clipArea = clipArea;
		this.intersectionArea = intersectionArea;
		this.unionArea = unionArea;
		this.calculatedUnion = calculatedUnion;
	}
}

public class GuiTest implements Presenter.View, ActionListener, ChangeListener {
	private JFrame frame = new JFrame();
	private PolygonCanvas canvas = new PolygonCanvas();
	
	private JRadioButton intersection = new JRadioButton("Intersection");
	private JRadioButton union = new JRadioButton("Union");
	private JRadioButton difference = new JRadioButton("Difference");
	private JRadioButton xor = new JRadioButton("XOR");
	private JRadioButton none = new JRadioButton("None");
	
	private JRadioButton evenOdd = new JRadioButton("EvenOdd");
	private JRadioButton nonZero = new JRadioButton("NonZero");
	
	private JSpinner countSpinner = new JSpinner();
	
	private JSpinner offsetSpinner = new JSpinner();
	
	private JRadioButton circles = new JRadioButton("Circles");
	private JRadioButton polygons = new JRadioButton("Polygons");
	
	private JButton newButton = new JButton("New Sample");
	
	private Callback updateCallback;
	
	public GuiTest() {
		frame.setVisible(true);
		frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
		frame.setTitle("Clipper Java Demo");
		
		frame.setLayout(new BorderLayout());
		frame.add(canvas, BorderLayout.CENTER);
		
		JPanel sidePanel = new JPanel();
		frame.add(sidePanel, BorderLayout.WEST);
		
		JPanel controlPanel = new JPanel();
		sidePanel.add(controlPanel);
		controlPanel.setLayout(new BoxLayout(controlPanel, BoxLayout.Y_AXIS));
		
		// Operation
		JComponent operationButtons = groupRadioButtons("Operation", intersection, union, difference, xor, none);
		operationButtons.setAlignmentX(Component.LEFT_ALIGNMENT);
		controlPanel.add(operationButtons);
		
		controlPanel.add(Box.createRigidArea(new Dimension(0, 5)));
		
		// Fill Type
		JComponent fillTypeButtons = groupRadioButtons("Fill Type", evenOdd, nonZero);
		fillTypeButtons.setAlignmentX(Component.LEFT_ALIGNMENT);
		controlPanel.add(fillTypeButtons);
		
		// Count
		JPanel countPanel = new JPanel();
		countSpinner.setModel(new SpinnerNumberModel(5, 5, 50, 1));
		countPanel.setBorder(BorderFactory.createTitledBorder("Count"));
		countPanel.add(countSpinner);
		countPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
		controlPanel.add(countPanel);
		
		controlPanel.add(Box.createRigidArea(new Dimension(0, 5)));
		
		// Offset
		JPanel offsetPanel = new JPanel();
		offsetSpinner.setModel(new SpinnerNumberModel(0, -10, 10, 1));
		offsetPanel.setBorder(BorderFactory.createTitledBorder("Offset"));
		offsetPanel.add(offsetSpinner);
		offsetPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
		controlPanel.add(offsetPanel);
		
		controlPanel.add(Box.createRigidArea(new Dimension(0, 5)));
		
		// Sample
		JComponent sampleButtons = groupRadioButtons("Sample", circles, polygons);
		sampleButtons.setAlignmentX(Component.LEFT_ALIGNMENT);
		controlPanel.add(sampleButtons);
		
		controlPanel.add(Box.createRigidArea(new Dimension(0, 5)));
		
		// New
		newButton.setAlignmentX(Component.LEFT_ALIGNMENT);
		controlPanel.add(newButton);
		
		union.setSelected(true);
		nonZero.setSelected(true);
		circles.setSelected(true);
		
		intersection.addActionListener(this);
		union.addActionListener(this);
		difference.addActionListener(this);
		xor.addActionListener(this);
		none.addActionListener(this);
		countSpinner.addChangeListener(this);
		offsetSpinner.addChangeListener(this);
		circles.addActionListener(this);
		polygons.addActionListener(this);
		newButton.addActionListener(this);
		evenOdd.addActionListener(this);
		nonZero.addActionListener(this);
	}
	
	private static JComponent groupRadioButtons(String title, JRadioButton... buttons) {
		JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		panel.setBorder(BorderFactory.createTitledBorder(title));
		
		ButtonGroup buttonGroup = new ButtonGroup();
		for(JRadioButton button:buttons) {
			buttonGroup.add(button);
			panel.add(button);
		}
		
		return panel;
	}

	@Override
	public void setSize(int width, int height) {
		frame.setSize(width, height);
	}
	
	@Override
	public void setSubjectPolygons(List<Polygon> polygons) {
		canvas.setSubjectPolygons(polygons);
	}

	@Override
	public void setClipPolygons(List<Polygon> polygons) {
		canvas.setClipPolygons(polygons);
	}

	@Override
	public void setSolutionPolygons(List<Polygon> polygons) {
		canvas.setSolutionPolygons(polygons);
	}

	@Override
	public void setScale(long scale) {
		canvas.setScale(scale);
	}

	@Override
	public void setUpdateCallback(Callback callback) {
		this.updateCallback = callback;
	}

	@Override
	public Operation getOperation() {
		if(intersection.isSelected()) return Operation.INTERSECTION;
		if(union.isSelected()) return Operation.UNION;
		if(difference.isSelected()) return Operation.DIFFERENCE;
		if(xor.isSelected()) return Operation.XOR;
		return Operation.NONE;
	}

	@Override
	public FillType getFillType() {
		if(evenOdd.isSelected()) return FillType.EVENODD;
		if(nonZero.isSelected()) return FillType.NONZERO;
		return FillType.EVENODD;
	}
	
	@Override
	public int getCount() {
		return (Integer)countSpinner.getValue();
	}

	@Override
	public int getOffset() {
		return (Integer)offsetSpinner.getValue();
	}

	@Override
	public Sample getSample() {
		if(circles.isSelected()) return Sample.CIRCLES;
		return Sample.POLYGONS;
	}

	@Override
	public void actionPerformed(ActionEvent e) {
		if(updateCallback != null) {
			updateCallback.execute();
		}
	}

	@Override
	public void stateChanged(ChangeEvent e) {
		if(updateCallback != null) {
			updateCallback.execute();
		}
	}

	@Override
	public void setAreas(double subjectArea, double clipArea, double intersectionArea, double unionArea, double calculatedUnion) {
		canvas.setAreas(subjectArea, clipArea, intersectionArea, unionArea, calculatedUnion);
	}
}
