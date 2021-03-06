-- part 1: can be applied on archive running old archive 5.14
alter table patient add verification_status int;
alter table patient add failed_verifications int;
alter table patient add verification_time datetime2;

alter table stgcmt_result add batch_id varchar(255);
alter table stgcmt_result add msg_id varchar(255);

create index UK_bay8wkvwegw3pmyeypv2v93k1 on patient (verification_time);

create index UK_f718gnu5js0mdg39q6j7fklia on stgcmt_result (batch_id);
create index UK_4iih0m0ueyvaim3033di45ems on stgcmt_result (msg_id);

update patient set verification_status = 0, failed_verifications = 0;

-- part 2: have to be applied while archive is stopped
update patient set verification_status = 0, failed_verifications = 0
  where verification_status is null;

-- part 3: can be applied after already starting archive 5.15
alter table patient alter column verification_status int not null;
create index UK_e7rsyrt9n2mccyv1fcd2s6ikv on patient (verification_status);
alter table patient alter column failed_verifications int not null;