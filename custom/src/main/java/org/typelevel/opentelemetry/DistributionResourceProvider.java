package org.typelevel.opentelemetry;

import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.autoconfigure.spi.ResourceProvider;
import io.opentelemetry.sdk.resources.Resource;

public class DistributionResourceProvider implements ResourceProvider {

    @Override
    public Resource createResource(ConfigProperties config) {
        return DistributionResource.get();
    }

    /**
     * Make sure we have a higher priority than the default resource provider for otel.distro.name
     * and otel.distro.version.
     */
    @Override
    public int order() {
        return Integer.MAX_VALUE;
    }

}
