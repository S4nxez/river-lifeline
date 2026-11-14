package com.riverlifeline.monitoring.application.service;

import com.riverlifeline.monitoring.application.exception.InvalidParameterException;
import com.riverlifeline.monitoring.application.port.in.PassQueries;
import com.riverlifeline.monitoring.application.port.out.CatalogRepository;
import com.riverlifeline.monitoring.application.port.out.LayerImageRepository;
import com.riverlifeline.monitoring.application.view.Views.LayerContent;
import com.riverlifeline.monitoring.application.view.Views.PassDetail;
import com.riverlifeline.monitoring.application.view.Views.PassEntry;
import com.riverlifeline.monitoring.domain.exception.LayerNotFoundException;
import com.riverlifeline.monitoring.domain.model.catalog.Catalog;
import com.riverlifeline.monitoring.domain.model.pass.LayerId;
import com.riverlifeline.monitoring.domain.model.pass.LayerImage;
import com.riverlifeline.monitoring.domain.model.pass.Pass;
import com.riverlifeline.monitoring.domain.model.pass.PassSelector;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

public class PassQueryService implements PassQueries {
    private final Catalogs catalogs;
    private final LayerImageRepository layerImages;

    public PassQueryService(CatalogRepository catalogs, LayerImageRepository layerImages) {
        this.catalogs = new Catalogs(catalogs);
        this.layerImages = Objects.requireNonNull(layerImages, "layerImages");
    }

    @Override
    public List<PassEntry> listPasses(String aoiId, Boolean evaluated, LocalDate from, LocalDate to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new InvalidParameterException("from", "'from' (" + from + ") is after 'to' (" + to + ").");
        }
        Catalog catalog = catalogs.load(aoiId);
        return catalog.passes().stream()
                .filter(pass -> evaluated == null || pass.evaluated() == evaluated)
                .filter(pass -> from == null || !pass.acquiredOn().isBefore(from))
                .filter(pass -> to == null || !pass.acquiredOn().isAfter(to))
                .map(pass -> entryOf(catalog, pass))
                .toList();
    }

    @Override
    public PassDetail getPass(String aoiId, PassSelector selector) {
        Catalog catalog = catalogs.load(aoiId);
        return new PassDetail(catalog.aoiId(), entryOf(catalog, catalog.require(selector)));
    }

    @Override
    public LayerContent layer(String aoiId, PassSelector selector, String layerId) {
        Catalog catalog = catalogs.load(aoiId);
        Pass pass = catalog.require(selector);
        LayerId layer = LayerId.fromCode(layerId)
                .orElseThrow(() -> new LayerNotFoundException(catalog.aoiId(), pass.id(), layerId));
        LayerImage image =
                pass.layer(layer).orElseThrow(() -> new LayerNotFoundException(catalog.aoiId(), pass.id(), layerId));
        return layerImages
                .open(catalog.aoiId(), image)
                .map(content -> new LayerContent(pass.id(), layer, !selector.isLatest(), content))
                .orElseThrow(() -> new LayerNotFoundException(catalog.aoiId(), pass.id(), layerId));
    }

    static PassEntry entryOf(Catalog catalog, Pass pass) {
        return new PassEntry(
                pass,
                catalog.previousOf(pass).map(Pass::id).orElse(null),
                catalog.nextOf(pass).map(Pass::id).orElse(null));
    }
}
