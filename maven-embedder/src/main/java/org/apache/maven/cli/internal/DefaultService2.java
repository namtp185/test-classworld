package org.apache.maven.cli.internal;

import javax.inject.Inject;
import javax.inject.Named;

import org.codehaus.plexus.DefaultPlexusContainer;
import org.codehaus.plexus.PlexusContainer;
import org.codehaus.plexus.classworlds.ClassWorld;
import org.codehaus.plexus.classworlds.realm.ClassRealm;
import org.codehaus.plexus.logging.Logger;

@Named
public class DefaultService2 {
	private final Logger log;

	private final ClassWorld classWorld;

	private final ClassRealm parentRealm;

	@Inject
	public DefaultService2(Logger log, PlexusContainer container) {
		this.log = log;
		this.classWorld = ((DefaultPlexusContainer) container).getClassWorld();
		this.parentRealm = container.getContainerRealm();
	}

	public void doThing() {
		System.out.println("Service " + getClass().getName().toString() + " start!");
	}
	
}
