package davUploader;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Frame;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Label;
import java.awt.Panel;
import java.awt.ScrollPane;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DropTarget;
import java.awt.dnd.DropTargetDragEvent;
import java.awt.dnd.DropTargetDropEvent;
import java.awt.dnd.DropTargetEvent;
import java.awt.dnd.DropTargetListener;
import java.awt.event.WindowEvent;
import java.awt.event.WindowListener;
import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.LinkedHashSet;
import java.util.List;

import javax.swing.JPasswordField;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;

import davUploader.UploadOperation.State;

/**
 * @author Pingger
 *
 */
public class UI implements WindowListener, DropTargetListener, Runnable
{
	private Thread							currentJobsThread	= null;
	private UploadOperation					currentOperation	= null;
	private LinkedHashSet<UploadOperation>	finishedOperations	= new LinkedHashSet<>();

	private Frame							frame;
	private Panel							jobPanel;
	private JSpinner						jsp_speedLimit;
	private JTextField						jtf_pass;
	private JTextField						jtf_path;
	private JTextField						jtf_server;

	private JTextField						jtf_user;

	private LinkedHashSet<UploadOperation>	pendingOperations	= new LinkedHashSet<>();

	public UI() throws MalformedURLException
	{
		frame = new Frame("Pinggers Nextcloud WebDAV Uploader");
		frame.addWindowListener(this);
		DropTarget dt = new DropTarget(frame, DnDConstants.ACTION_COPY_OR_MOVE, this, true);

		frame.setLayout(new BorderLayout());
		Panel p = new Panel();
		p.setLayout(new BorderLayout());
		frame.add(p, BorderLayout.NORTH);
		ScrollPane sp = new ScrollPane();
		frame.add(sp, BorderLayout.CENTER);
		Panel jobPanelWrapper = new Panel();
		jobPanelWrapper.setLayout(new BorderLayout());
		jobPanel = new Panel();
		jobPanel.setLayout(new GridLayout(0, 1));
		jobPanelWrapper.add(jobPanel, BorderLayout.NORTH);
		sp.add(jobPanelWrapper);

		Panel config = new Panel();
		GridBagLayout gbl = new GridBagLayout();
		gbl.rowWeights = new double[] {
				1.0, 1.0, 1.0, 1.0, 1.0
		};
		gbl.columnWidths = new int[] {
				160, -1
		};
		GridBagConstraints gbc = new GridBagConstraints();
		config.setLayout(gbl);
		gbc.gridx = 0;
		gbc.gridy = 0;
		gbc.anchor = GridBagConstraints.BASELINE_LEADING;
		gbc.fill = GridBagConstraints.BOTH;
		config.add(new Label("Server: "), gbc);
		gbc.gridx = (gbc.gridx + 1) % 2;
		config.add(jtf_server = new JTextField("https://nextcloud.iskariot.info"), gbc);
		gbc.gridy += gbc.gridx == 1 ? 1 : 0;
		gbc.gridx = (gbc.gridx + 1) % 2;
		config.add(new Label("User: "), gbc);
		gbc.gridy += gbc.gridx == 1 ? 1 : 0;
		gbc.gridx = (gbc.gridx + 1) % 2;
		config.add(jtf_user = new JTextField(""), gbc);
		gbc.gridy += gbc.gridx == 1 ? 1 : 0;
		gbc.gridx = (gbc.gridx + 1) % 2;
		config.add(new Label("Password: "), gbc);
		gbc.gridy += gbc.gridx == 1 ? 1 : 0;
		gbc.gridx = (gbc.gridx + 1) % 2;
		config.add(jtf_pass = new JPasswordField(""), gbc);
		jtf_pass.addActionListener(l -> System.out.println(jtf_pass.getText()));
		gbc.gridy += gbc.gridx == 1 ? 1 : 0;
		gbc.gridx = (gbc.gridx + 1) % 2;
		config.add(new Label("Path: "), gbc);
		gbc.gridy += gbc.gridx == 1 ? 1 : 0;
		gbc.gridx = (gbc.gridx + 1) % 2;
		config.add(jtf_path = new JTextField("upload"), gbc);
		gbc.gridy += gbc.gridx == 1 ? 1 : 0;
		gbc.gridx = (gbc.gridx + 1) % 2;
		config.add(new Label("Speedlimit (in KiB/s): "), gbc);
		gbc.gridy += gbc.gridx == 1 ? 1 : 0;
		gbc.gridx = (gbc.gridx + 1) % 2;
		config.add(jsp_speedLimit = new JSpinner(new SpinnerNumberModel(8, 0, 1024 * 1024, 256)), gbc);
		frame.add(config, BorderLayout.NORTH);
		frame.setMinimumSize(new Dimension(768, 512));
		frame.pack();
		frame.setVisible(true);
		Thread t = new Thread(this);
		t.setDaemon(true);
		t.start();
	}

	public void add(File f)
	{
		try {
			UploadOperation uo = new UploadOperation(
					f, new URL(jtf_server.getText() + "/remote.php/dav/files/" + jtf_user.getText() + "/" + jtf_path.getText() + "/" + f.getName()),
					jtf_user.getText(), jtf_pass.getText()
			);
			add(uo);
		}
		catch (MalformedURLException e) {
			e.printStackTrace();
		}
	}

	public void add(UploadOperation uo)
	{
		System.out.println("Adding: " + uo.source.getAbsolutePath());
		if (uo.getCurrentState() == State.COMPLETED || uo.getCurrentState() == State.CANCELLED) {
			finishedOperations.add(uo);
			jobPanel.add(uo);
		}
		else {
			pendingOperations.add(uo);

			uo.markQueued();
			jobPanel.removeAll();
			for (UploadOperation uo2 : pendingOperations) {
				jobPanel.add(uo2);
			}
			for (UploadOperation uo2 : finishedOperations) {
				jobPanel.add(uo2);
			}
			jobPanel.revalidate();
		}
		uo.setParent(this);
	}

	@Override
	public void dragEnter(DropTargetDragEvent dtde)
	{
		if (!dtde.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
			dtde.rejectDrag();
		}
		dtde.acceptDrag(DnDConstants.ACTION_COPY_OR_MOVE);
	}

	@Override
	public void dragExit(DropTargetEvent dte)
	{
	}

	@Override
	public void dragOver(DropTargetDragEvent dtde)
	{
		if (!dtde.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
			dtde.rejectDrag();
		}
		dtde.acceptDrag(DnDConstants.ACTION_COPY_OR_MOVE);
	}

	@Override
	public void drop(DropTargetDropEvent dtde)
	{
		if (!dtde.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
			dtde.rejectDrop();
		}
		if (dtde.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
			List<File> files;
			try {
				dtde.acceptDrop(DnDConstants.ACTION_COPY);
				files = (List<File>) dtde.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
				files.forEach(f -> add(f));
			}
			catch (UnsupportedFlavorException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}

	}

	@Override
	public void dropActionChanged(DropTargetDragEvent dtde)
	{
		// TODO Auto-generated method stub

	}

	public void remove(UploadOperation uo)
	{
		finishedOperations.remove(uo);
		pendingOperations.remove(uo);
		jobPanel.remove(uo);
		uo.removeParent();
	}

	@Override
	public void run()
	{
		while (frame.isDisplayable()) {
			try {
				if (currentOperation != null) {
					currentOperation.setSpeedlimit((Integer) jsp_speedLimit.getValue() * 1024);
				}
				if (currentJobsThread == null || !currentJobsThread.isAlive()) {
					currentOperation = null;
					if (pendingOperations.size() > 0) {
						currentOperation = pendingOperations.iterator().next();
						if (currentOperation.getCurrentState() == State.COMPLETED || currentOperation.getCurrentState() == State.CANCELLED) {
							remove(currentOperation);
							add(currentOperation);
							continue;
						}
						currentJobsThread = new Thread(currentOperation);
						currentJobsThread.setName("UploadJob: " + currentOperation.toString());
						currentJobsThread.setDaemon(true);
						currentOperation.setSpeedlimit((Integer) jsp_speedLimit.getValue() * 1024);
						currentJobsThread.start();
					}
					else {
						currentJobsThread = null;
					}
				}
			}
			catch (Exception exc) {
				exc.printStackTrace();
			}
			try {
				Thread.sleep(500);
			}
			catch (InterruptedException e) {

			}
			frame.repaint();
		}
		System.out.println("Exit");
	}

	@Override
	public void windowActivated(WindowEvent e)
	{

	}

	@Override
	public void windowClosed(WindowEvent e)
	{

	}

	@Override
	public void windowClosing(WindowEvent e)
	{
		frame.dispose();
	}

	@Override
	public void windowDeactivated(WindowEvent e)
	{

	}

	@Override
	public void windowDeiconified(WindowEvent e)
	{

	}

	@Override
	public void windowIconified(WindowEvent e)
	{

	}

	@Override
	public void windowOpened(WindowEvent e)
	{

	}

}
