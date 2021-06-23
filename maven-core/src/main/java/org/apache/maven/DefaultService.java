package org.apache.maven;

import org.codehaus.plexus.component.annotations.Component;

/**
 * @author Jason van Zyl
 */
@Component(role = Service.class)
public class DefaultService implements Service {
	public void doThing() {
		System.out.println("Service start!");
	}
}
