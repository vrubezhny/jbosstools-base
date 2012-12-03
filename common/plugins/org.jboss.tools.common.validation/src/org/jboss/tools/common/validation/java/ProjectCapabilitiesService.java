/*******************************************************************************
 * Copyright (c) 2012 Red Hat, Inc.
 * Distributed under license by Red Hat, Inc. All rights reserved.
 * This program is made available under the terms of the
 * Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributor:
 *     Red Hat, Inc. - initial API and implementation
 ******************************************************************************/
package org.jboss.tools.common.validation.java;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResourceChangeListener;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.IResourceDeltaVisitor;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.runtime.CoreException;
import org.jboss.tools.common.validation.CommonValidationPlugin;

/**
 * 
 * @author Victor V. Rubezhny
 *
 */
public class ProjectCapabilitiesService implements IResourceChangeListener {
	private static ProjectCapabilitiesService instance;
	private boolean isShuttedDown = false;
	
	public interface IProjectCapabilitiesListener {
		void projectCapabilitiesChanged(String[] naturesAdded, String[] naturesRemoved);
	}

	private Map<IProject, Set<IProjectCapabilitiesListener>> projectListeners = new HashMap<IProject, Set<IProjectCapabilitiesListener>>();
	private Map<IProject, Set<String>> projectCapabilities = new HashMap<IProject, Set<String>>();
	private Map<IWorkspace, Set<IProject>> workspaceProjects = new HashMap<IWorkspace, Set<IProject>>();
	
	private ProjectCapabilitiesService() {
		
	}
	
	public static ProjectCapabilitiesService getDefault() {
		if (instance == null) {
			instance = new ProjectCapabilitiesService();
		}
		
		return instance;
	}
	
	public void addProjectCapabilitiesListener(IProject project, IProjectCapabilitiesListener listener) {
		if (isShuttedDown || project == null || listener == null)
			return;

		startWatchingProject(project);

		Set<IProjectCapabilitiesListener> listeners = projectListeners.get(project);
		if (listeners == null) { 
			listeners = new HashSet<IProjectCapabilitiesListener>(1);
			projectListeners.put(project, listeners);
		}
		if (!listeners.contains(listener)) {
			listeners.add(listener);
		}
	}
	
	public void removeProjectCapabilitiesListener(IProject project, IProjectCapabilitiesListener listener) {
		Set<IProjectCapabilitiesListener> listeners = projectListeners.get(project);
		
		if (listeners != null) {
			if (listeners.contains(listener))
				listeners.remove(listener);
		}
		if (listeners == null || listeners.isEmpty()) {
			stopWatchingProject(project);
		}
	}
	
	private Set<String> getProjectCapabilities(IProject project) {
		Set<String> projectNatureIds = new HashSet<String>();
		try {
			String[] natureIds = project.getDescription().getNatureIds();
			for (String natureId : natureIds) {
				natureId = natureId.trim();
				if (!projectNatureIds.contains(natureId))
					projectNatureIds.add(natureId);
			}
		} catch (CoreException e) {
			CommonValidationPlugin.getDefault().logError(e);
		}
		return projectNatureIds;
	}
	
	private void startWatchingProject(IProject project) {
		if (isShuttedDown || project == null)
			return;

		if(projectCapabilities.containsKey(project))
			return; // Already initialized
		
		projectCapabilities.put(project, getProjectCapabilities(project));

		IWorkspace workspace = project.getWorkspace();

		Set<IProject> projects = workspaceProjects.get(workspace);
		if (projects == null) {
			projects = new HashSet<IProject>(1);
			workspace.addResourceChangeListener(this);
			workspaceProjects.put(workspace, projects);
		}
		
		if (!projects.contains(project))
			projects.add(project);
	}
	
	private void stopWatchingProject(IProject project) {
		if (project == null)
			return;
		
		IWorkspace workspace = project.getWorkspace();
		Set<IProject> projects = workspaceProjects.get(workspace);
		if (projects != null) { 
			projects.remove(project);
		}
		
		if (workspaceProjects.containsKey(workspace) && (projects == null || projects.isEmpty())) {
			workspace.removeResourceChangeListener(this);
			workspaceProjects.remove(workspace);
		}
		
		if (projectCapabilities.containsKey(project)) {
			projectCapabilities.remove(project);
		}
	}
	
	private void cleanup() {
		if (isShuttedDown)
			return;
		
		isShuttedDown = true;
		for (IProject p : projectListeners.keySet()) {
			Set<IProjectCapabilitiesListener> listeners = projectListeners.get(p);
			if (p != null) {
				for (IProjectCapabilitiesListener l : listeners) {
					removeProjectCapabilitiesListener(p, l);
				}
			}
			stopWatchingProject(p); // Just for sure
		}
	}
	
	public static void shutdown() {
		getDefault().cleanup();
	}
	
	class DotProjectChangeDetector implements IResourceDeltaVisitor {
		IResourceDelta rootDelta;
		IProject detectionResult;
		
		DotProjectChangeDetector(IResourceDelta rootDelta) {
			this.rootDelta = rootDelta;
			this.detectionResult = null;
		}
		
		public IProject getChangedProject() {
			if (detectionResult != null)
				return detectionResult;
			
			try {
				rootDelta.accept(this);
			} catch (CoreException e) {
				CommonValidationPlugin.getDefault().logError(e);
			}

			return detectionResult;
		}
		
		@Override
		public boolean visit(IResourceDelta delta) throws CoreException {
			IResource resource = delta.getResource();
			
			if (resource instanceof IWorkspaceRoot || resource instanceof IWorkspace) {
				return true;
			}
			
			if (resource instanceof IProject) {
				return true;
			}

			// ".project" file can be placed only in project's root
			if (resource instanceof IFolder) {
				return false;
			}
			
			if (resource instanceof IFile) {
				detectionResult =  (IProjectDescription.DESCRIPTION_FILE_NAME.equals(resource.getName()) ?
						resource.getProject() : null);
			}

			return false;
		}
	}


	public void resourceChanged(IResourceChangeEvent event) {
		IResourceDelta delta = event.getDelta();
		
		DotProjectChangeDetector visitor = new DotProjectChangeDetector(delta);
		IProject changedProject = visitor.getChangedProject();
		if (changedProject != null) {
			Set<String> newProjectCapabilities = getProjectCapabilities(changedProject);

			// Check if we're watching this project
			Set<IProjectCapabilitiesListener> listeners = projectListeners.get(changedProject);
			if (listeners != null && !listeners.isEmpty()) {
				Set<String> oldProjectCapabilities = projectCapabilities.get(changedProject);
			
				Set<String> addedNatureIds = new HashSet<String>();
				for (String natureId : newProjectCapabilities) {
					if (!oldProjectCapabilities.contains(natureId))
						addedNatureIds.add(natureId);
				}
				
				Set<String> removedNatureIds = new HashSet<String>();
				for (String natureId : oldProjectCapabilities) {
					if (!newProjectCapabilities.contains(natureId))
						removedNatureIds.add(natureId);
				}
	
				if (!addedNatureIds.isEmpty() || !removedNatureIds.isEmpty()) {
					for (IProjectCapabilitiesListener l : listeners) {
						l.projectCapabilitiesChanged(addedNatureIds.toArray(new String[0]), removedNatureIds.toArray(new String[0]));
					}
				}
			}
			if (projectCapabilities.containsKey(changedProject)) {
				// Update current project capabilities
				projectCapabilities.put(changedProject, newProjectCapabilities);
			}
		}
	}
}