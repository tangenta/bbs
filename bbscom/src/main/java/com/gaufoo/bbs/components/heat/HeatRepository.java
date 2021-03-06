package com.gaufoo.bbs.components.heat;

import java.util.stream.Stream;

public interface HeatRepository {

    boolean saveHeat(String heatGroup, String id, long init);

    Long getHeat(String heatGroup, String id);

    boolean updateHeat(String heatGroup, String id, long value);

    Stream<String> getAllAsc(String heatGroup);

    Stream<String> getAllDes(String heatGroup);

    boolean delete(String heatGroup, String id);

    boolean delete(String heatGroup);

    default void shutdown() { }
}
