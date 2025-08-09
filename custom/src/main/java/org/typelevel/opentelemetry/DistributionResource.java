package org.typelevel.opentelemetry;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.resources.Resource;

public class DistributionResource {
    public static final AttributeKey<String> DISTRIBUTION_NAME =
            AttributeKey.stringKey("telemetry.distro.name");

    private static final Resource INSTANCE = buildResource();

    private DistributionResource() {}

    public static Resource get() {
        return INSTANCE;
    }

    static Resource buildResource() {
        return Resource.create(
                Attributes.of(
                        DISTRIBUTION_NAME,
                        "otel4s-opentelemetry-java"));
    }
}
