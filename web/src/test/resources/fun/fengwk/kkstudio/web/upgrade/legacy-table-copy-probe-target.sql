-- target 独有的即时约束与父行：source 没有这些约束，因此可以合法持有「故意不匹配」的源数据。
alter table legacy_copy_probe
    add constraint fk_legacy_copy_probe_parent foreign key (parent_id)
        references legacy_copy_probe_parent (id);
alter table legacy_copy_probe
    add constraint uk_legacy_copy_probe_unique_key unique (unique_key);

insert into legacy_copy_probe_parent (id, label)
    values ('00000000-0000-4000-8000-00000000a001', 'legacy probe parent');
