CREATE TABLE hospitals (
    hospital_id serial PRIMARY KEY,
    name text NOT NULL,
    inn char(10) UNIQUE NOT NULL,
    address text,
    phone varchar(20)
);

CREATE TABLE departments (
    department_id serial PRIMARY KEY,
    hospital_id int NOT NULL REFERENCES hospitals(hospital_id) ON DELETE CASCADE,
    name text NOT NULL,
    head_doctor_id int, 
    UNIQUE (hospital_id, name)
);

CREATE TABLE positions (
    position_id serial PRIMARY KEY,
    title text NOT NULL UNIQUE
);

CREATE TABLE doctors (
    doctor_id serial PRIMARY KEY,
    hospital_id int NOT NULL REFERENCES hospitals(hospital_id) ON DELETE CASCADE,
    department_id int NOT NULL, 
    inn varchar(12) UNIQUE NOT NULL,
    name text NOT NULL,
    position_id int NOT NULL REFERENCES positions(position_id)
);

ALTER TABLE doctors
    ADD CONSTRAINT fk_doctor_department FOREIGN KEY (department_id)
    REFERENCES departments(department_id) ON DELETE RESTRICT;

ALTER TABLE departments
    ADD CONSTRAINT fk_dept_head_doctor FOREIGN KEY (head_doctor_id)
    REFERENCES doctors(doctor_id) ON DELETE SET NULL;

CREATE TABLE diagnoses (
    diagnosis_id serial PRIMARY KEY,
    name text NOT NULL,
    treatment_notes text
);

CREATE TABLE patients (
    patient_id serial PRIMARY KEY,
    inn varchar(12),
    name text NOT NULL,
    birth_date date CHECK (birth_date <= CURRENT_DATE),
    gender char(1) CHECK (gender IN ('m','f')),
    phone varchar(20),
    address text
);

CREATE TABLE appointments (
    appointment_id serial PRIMARY KEY,
    patient_id int NOT NULL REFERENCES patients(patient_id) ON DELETE CASCADE,
    doctor_id int NOT NULL REFERENCES doctors(doctor_id) ON DELETE CASCADE,
    department_id int NOT NULL REFERENCES departments(department_id) ON DELETE CASCADE,
    hospital_id int NOT NULL REFERENCES hospitals(hospital_id) ON DELETE CASCADE,
    appointment_start timestamptz NOT NULL,
    appointment_end timestamptz NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'scheduled' 
        CHECK (status IN ('scheduled', 'completed', 'cancelled')),
    diagnosis_id int REFERENCES diagnoses(diagnosis_id),
    notes text
);